package dev.lastro.core.pix;

import dev.lastro.core.pix.PixSettlement.Outcome;
import dev.lastro.core.pix.SentinelClient.Analysis;
import dev.lastro.core.pix.SentinelClient.AnalysisRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Leva os pedidos da outbox ao sentinel. Nenhuma trava fica aberta durante a chamada de rede: o
 * pedido é marcado com um prazo de posse, e se esta instância cair, outra retoma quando o prazo vencer.
 */
@Service
class AnalysisWorker {

    static final Duration PENDING_LIMIT = Duration.ofMinutes(10);
    static final Duration REVIEW_LIMIT = Duration.ofHours(24);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final int BATCH = 20;
    private static final Logger log = LoggerFactory.getLogger(AnalysisWorker.class);

    private record Claim(UUID transferId, int attempts) {
    }

    private final JdbcClient jdbc;
    private final SentinelClient sentinel;
    private final PixSettlement settlement;
    private final JsonMapper json;
    private final Clock clock;

    AnalysisWorker(JdbcClient jdbc, SentinelClient sentinel, PixSettlement settlement, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.sentinel = sentinel;
        this.settlement = settlement;
        this.json = json;
        this.clock = clock;
    }

    /** Uma passada: cancela o que passou do prazo e analisa o que está na fila. Devolve quantos decidiu. */
    public int runOnce() {
        expire();
        int decided = 0;
        for (Claim claim : claim()) {
            Optional<AnalysisRequest> request = request(claim.transferId());
            if (request.isEmpty()) {
                settlement.decide(claim.transferId(), Outcome.CANCELLED, null, null);
                continue;
            }
            try {
                Analysis analysis = sentinel.analyze(request.get());
                Outcome outcome = switch (analysis.decision()) {
                    case APPROVED -> Outcome.SETTLED;
                    case DENIED -> Outcome.DENIED;
                    case REVIEW -> Outcome.UNDER_REVIEW;
                };
                if (settlement.decide(claim.transferId(), outcome, analysis.score(),
                        json.writeValueAsString(analysis.reasons()))) {
                    decided++;
                }
            } catch (RuntimeException e) {
                log.warn("Análise do Pix {} falhou na tentativa {}: {}", claim.transferId(), claim.attempts(), e.getMessage());
                retryLater(claim);
            }
        }
        return decided;
    }

    private void expire() {
        OffsetDateTime now = now();
        List<UUID> late = jdbc.sql("""
                        SELECT id FROM core.pix_transfer
                         WHERE (status = 'PENDING' AND created_at < :pending)
                            OR (status = 'UNDER_REVIEW' AND created_at < :review)""")
                .param("pending", now.minus(PENDING_LIMIT)).param("review", now.minus(REVIEW_LIMIT))
                .query(UUID.class).list();
        for (UUID id : late) {
            settlement.decide(id, Outcome.CANCELLED, null, null);
        }
    }

    /** SKIP LOCKED: várias instâncias rodando juntas nunca pegam o mesmo pedido. */
    private List<Claim> claim() {
        OffsetDateTime now = now();
        return jdbc.sql("""
                        UPDATE core.outbox SET claimed_until = :lease, attempts = attempts + 1
                         WHERE id IN (SELECT id FROM core.outbox
                                       WHERE kind = 'PIX_ANALYSIS' AND available_at <= :now
                                         AND (claimed_until IS NULL OR claimed_until < :now)
                                       ORDER BY created_at LIMIT :batch FOR UPDATE SKIP LOCKED)
                        RETURNING aggregate_id, attempts""")
                .param("lease", now.plus(LEASE)).param("now", now).param("batch", BATCH)
                .query((rs, n) -> new Claim(rs.getObject("aggregate_id", UUID.class), rs.getInt("attempts")))
                .list();
    }

    /** Espera crescente, de 2 segundos até 1 minuto. */
    private void retryLater(Claim claim) {
        long seconds = Math.min(1L << Math.min(claim.attempts(), 6), 60);
        jdbc.sql("""
                        UPDATE core.outbox SET claimed_until = NULL, available_at = :at
                         WHERE kind = 'PIX_ANALYSIS' AND aggregate_id = :id""")
                .param("at", now().plusSeconds(seconds)).param("id", claim.transferId()).update();
    }

    private Optional<AnalysisRequest> request(UUID transferId) {
        return jdbc.sql("""
                        SELECT p.from_account, p.to_account, p.amount_cents, p.created_at, a.created_at AS account_created
                          FROM core.pix_transfer p JOIN core.account a ON a.id = p.from_account
                         WHERE p.id = :id AND p.status = 'PENDING'""")
                .param("id", transferId)
                .query((rs, n) -> new AnalysisRequest(transferId, rs.getObject("from_account", UUID.class),
                        rs.getObject("to_account", UUID.class), rs.getLong("amount_cents"),
                        rs.getObject("account_created", OffsetDateTime.class).toInstant(),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
    }
}
