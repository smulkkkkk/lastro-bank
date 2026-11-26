package dev.lastro.core.helpdesk;

import dev.lastro.core.helpdesk.Assistant.Line;
import dev.lastro.core.helpdesk.Assistant.Reply;
import dev.lastro.core.helpdesk.Assistant.Role;
import dev.lastro.core.helpdesk.Assistant.Snapshot;
import dev.lastro.core.helpdesk.Assistant.Turn;
import dev.lastro.core.notify.Notifier;
import dev.lastro.core.notify.Notifier.Kind;
import dev.lastro.core.shared.ApiException;
import dev.lastro.core.shared.RateLimiter;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * O chat com o Lino. A chamada à IA acontece fora de transação: nenhuma trava fica aberta enquanto
 * a resposta não chega.
 */
@Service
public class HelpdeskService {

    public record Message(long id, Role role, String text, String source, Instant createdAt) {
    }

    public record Conversation(UUID id, String status, String protocol, List<Message> messages) {
        static final Conversation EMPTY = new Conversation(null, "NEW", null, List.of());
    }

    static final int HISTORY = 20;
    static final int MESSAGES_PER_MINUTE = 10;
    private static final DateTimeFormatter PROTOCOL_DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("America/Sao_Paulo"));
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final Assistant assistant;
    private final Notifier notifier;
    private final RateLimiter limiter;
    private final JsonMapper json;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    HelpdeskService(JdbcClient jdbc, TransactionTemplate transaction, Assistant assistant, Notifier notifier,
            RateLimiter limiter, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.assistant = assistant;
        this.notifier = notifier;
        this.limiter = limiter;
        this.json = json;
        this.clock = clock;
    }

    /** A conversa mais recente, aberta ou já passada para uma pessoa. */
    public Conversation current(UUID userId) {
        return latest(userId).map(this::load).orElse(Conversation.EMPTY);
    }

    public Conversation send(UUID userId, String text) {
        limiter.check("chat:" + userId, MESSAGES_PER_MINUTE);
        UUID conversationId = transaction.execute(status -> {
            UUID id = openConversation(userId);
            insert(id, Role.USER, text.strip(), null);
            return id;
        });
        List<Turn> history = jdbc.sql("""
                        SELECT role, text FROM (SELECT id, role, text FROM core.support_message
                                                 WHERE conversation_id = :id ORDER BY id DESC LIMIT :n) last
                         ORDER BY id""")
                .param("id", conversationId).param("n", HISTORY)
                .query((rs, n) -> new Turn(Role.valueOf(rs.getString("role")), rs.getString("text")))
                .list();
        Reply reply = assistant.reply(snapshot(userId), history);
        String answer = reply.text().length() > 4000 ? reply.text().substring(0, 3999) + "…" : reply.text();
        insert(conversationId, Role.ASSISTANT, answer, reply.source());
        return load(conversationId);
    }

    /**
     * Passa a conversa para uma pessoa: dá protocolo, avisa o cliente e enfileira o envio ao n8n, que
     * abre o atendimento no WhatsApp. Tudo na mesma transação.
     */
    public Conversation handoff(UUID userId, String phone) {
        limiter.check("handoff:" + userId, 3);
        UUID conversationId = transaction.execute(status -> {
            UUID id = jdbc.sql("SELECT id FROM core.support_conversation WHERE user_id = :user AND status = 'OPEN' FOR UPDATE")
                    .param("user", userId).query(UUID.class).optional()
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "helpdesk.no_conversation",
                            "Conte primeiro para o Lino o que aconteceu, assim a pessoa já chega sabendo."));
            String protocol = protocol();
            OffsetDateTime now = now();
            jdbc.sql("""
                            UPDATE core.support_conversation
                               SET status = 'HANDED_OFF', protocol = :protocol, phone = :phone, handed_off_at = :now
                             WHERE id = :id""")
                    .param("protocol", protocol).param("phone", phone).param("now", now).param("id", id).update();
            insert(id, Role.ASSISTANT, "Pronto! Abri o protocolo " + protocol
                    + ". Uma pessoa do time vai chamar você no WhatsApp com o histórico desta conversa.", "FAQ");
            enqueueHandoff(id, userId, protocol, phone, now);
            notifier.send(userId, Kind.SUPPORT, "Atendimento aberto",
                    "Protocolo " + protocol + ". Uma pessoa vai falar com você no WhatsApp.", "/app/ajuda");
            return id;
        });
        return load(conversationId);
    }

    private void enqueueHandoff(UUID conversationId, UUID userId, String protocol, String phone, OffsetDateTime now) {
        String name = jdbc.sql("SELECT full_name FROM core.app_user WHERE id = :id").param("id", userId)
                .query(String.class).single();
        List<Map<String, Object>> transcript = load(conversationId).messages().stream()
                .map(m -> Map.<String, Object>of("role", m.role().name(), "text", m.text(), "at", m.createdAt().toString()))
                .toList();
        String payload = json.writeValueAsString(Map.of("protocol", protocol, "customerName", name,
                "phone", "+55" + phone, "createdAt", now.toInstant().toString(), "transcript", transcript));
        jdbc.sql("""
                        INSERT INTO core.outbox (id, kind, aggregate_id, payload, available_at, created_at)
                        VALUES (:id, 'SUPPORT_HANDOFF', :conversation, CAST(:payload AS jsonb), :now, :now)""")
                .param("id", UUID.randomUUID()).param("conversation", conversationId).param("payload", payload)
                .param("now", now).update();
    }

    /** ON CONFLICT: duas mensagens ao mesmo tempo não abrem duas conversas. */
    private UUID openConversation(UUID userId) {
        jdbc.sql("""
                        INSERT INTO core.support_conversation (id, user_id, status, created_at)
                        VALUES (:id, :user, 'OPEN', :now)
                        ON CONFLICT (user_id) WHERE status = 'OPEN' DO NOTHING""")
                .param("id", UUID.randomUUID()).param("user", userId).param("now", now()).update();
        return jdbc.sql("SELECT id FROM core.support_conversation WHERE user_id = :user AND status = 'OPEN'")
                .param("user", userId).query(UUID.class).single();
    }

    private void insert(UUID conversationId, Role role, String text, String source) {
        jdbc.sql("""
                        INSERT INTO core.support_message (conversation_id, role, text, source, created_at)
                        VALUES (:id, :role, :text, :source, :now)""")
                .param("id", conversationId).param("role", role.name()).param("text", text)
                .param("source", source).param("now", now()).update();
    }

    private Optional<UUID> latest(UUID userId) {
        return jdbc.sql("SELECT id FROM core.support_conversation WHERE user_id = :user ORDER BY created_at DESC LIMIT 1")
                .param("user", userId).query(UUID.class).optional();
    }

    private Conversation load(UUID id) {
        var head = jdbc.sql("SELECT status, protocol FROM core.support_conversation WHERE id = :id").param("id", id)
                .query((rs, n) -> new String[] {rs.getString("status"), rs.getString("protocol")}).single();
        List<Message> messages = jdbc.sql("""
                        SELECT id, role, text, source, created_at FROM core.support_message
                         WHERE conversation_id = :id ORDER BY id""")
                .param("id", id)
                .query((rs, n) -> new Message(rs.getLong("id"), Role.valueOf(rs.getString("role")), rs.getString("text"),
                        rs.getString("source"), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        return new Conversation(id, head[0], head[1], messages);
    }

    Snapshot snapshot(UUID userId) {
        String fullName = jdbc.sql("SELECT full_name FROM core.app_user WHERE id = :id").param("id", userId)
                .query(String.class).single();
        var totals = jdbc.sql("""
                        SELECT coalesce(sum(balance_cents - held_cents) FILTER (WHERE kind = 'CHECKING'), 0) AS available,
                               coalesce(sum(balance_cents) FILTER (WHERE kind = 'VAULT'), 0) AS vaults,
                               min(id::text) FILTER (WHERE kind = 'CHECKING') AS checking
                          FROM core.account WHERE owner_id = :id""")
                .param("id", userId)
                .query((rs, n) -> new Object[] {rs.getLong("available"), rs.getLong("vaults"), rs.getString("checking")})
                .single();
        List<Line> recent = totals[2] == null ? List.of() : jdbc.sql("""
                        SELECT description, direction, amount_cents, created_at
                          FROM core.account_statement(CAST(:account AS uuid), NULL, NULL, NULL, NULL, 5)""")
                .param("account", totals[2])
                .query((rs, n) -> new Line(rs.getString("description"), rs.getString("direction"),
                        rs.getLong("amount_cents"), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        return new Snapshot(fullName.strip().split("\\s+")[0], (long) totals[0], (long) totals[1], recent);
    }

    /** LST-20261125-7Q2K9A: data local e seis caracteres aleatórios. */
    private String protocol() {
        StringBuilder code = new StringBuilder("LST-").append(PROTOCOL_DAY.format(clock.instant())).append('-');
        for (int i = 0; i < 6; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
