package dev.lastro.core.notify;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Entrega o que está na outbox com conteúdo pronto (avisos, atendimento). Mesmo esquema do antifraude:
 * posse com prazo, SKIP LOCKED e nenhuma trava aberta durante a chamada de rede.
 */
@Service
public class DeliveryWorker {

    static final Duration GIVE_UP_AFTER = Duration.ofHours(24);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final int BATCH = 20;
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private record Claim(UUID id, String kind, String payload, int attempts, OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;
    private final Map<String, OutboxChannel> channels;
    private final Clock clock;

    public DeliveryWorker(JdbcClient jdbc, List<OutboxChannel> channels, Clock clock) {
        this.jdbc = jdbc;
        this.channels = channels.stream().collect(Collectors.toMap(OutboxChannel::kind, Function.identity()));
        this.clock = clock;
    }

    /** Uma passada. Devolve quantos entregou. */
    public int runOnce() {
        if (channels.isEmpty()) {
            return 0;
        }
        giveUp();
        int delivered = 0;
        for (Claim claim : claim()) {
            try {
                channels.get(claim.kind()).deliver(claim.id(), claim.payload());
                jdbc.sql("DELETE FROM core.outbox WHERE id = :id").param("id", claim.id()).update();
                delivered++;
            } catch (RuntimeException e) {
                log.warn("Entrega {} {} falhou na tentativa {}: {}", claim.kind(), claim.id(), claim.attempts(), e.getMessage());
                long seconds = Math.min(1L << Math.min(claim.attempts(), 6), 60);
                jdbc.sql("UPDATE core.outbox SET claimed_until = NULL, available_at = :at WHERE id = :id")
                        .param("at", now().plusSeconds(seconds)).param("id", claim.id()).update();
            }
        }
        return delivered;
    }

    /** Um aviso de ontem não serve mais para nada; fica o registro no log. */
    private void giveUp() {
        List<UUID> stale = jdbc.sql("DELETE FROM core.outbox WHERE kind IN (:kinds) AND created_at < :limit RETURNING id")
                .param("kinds", channels.keySet()).param("limit", now().minus(GIVE_UP_AFTER))
                .query(UUID.class).list();
        stale.forEach(id -> log.error("Entrega {} desistida depois de {}", id, GIVE_UP_AFTER));
    }

    private List<Claim> claim() {
        OffsetDateTime now = now();
        return jdbc.sql("""
                        UPDATE core.outbox SET claimed_until = :lease, attempts = attempts + 1
                         WHERE id IN (SELECT id FROM core.outbox
                                       WHERE kind IN (:kinds) AND available_at <= :now
                                         AND (claimed_until IS NULL OR claimed_until < :now)
                                       ORDER BY created_at LIMIT :batch FOR UPDATE SKIP LOCKED)
                        RETURNING id, kind, payload::text AS payload, attempts, created_at""")
                .param("lease", now.plus(LEASE)).param("now", now).param("kinds", channels.keySet())
                .param("batch", BATCH)
                .query((rs, n) -> new Claim(rs.getObject("id", UUID.class), rs.getString("kind"),
                        rs.getString("payload"), rs.getInt("attempts"), rs.getObject("created_at", OffsetDateTime.class)))
                // O RETURNING não garante ordem: os avisos saem na ordem em que aconteceram.
                .list().stream().sorted(Comparator.comparing(Claim::createdAt)).toList();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
