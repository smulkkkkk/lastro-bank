package dev.lastro.core.pix;

import dev.lastro.core.pix.PixSettlement.Outcome;
import dev.lastro.core.shared.ApiException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fila do analista: Pix que o sentinel mandou para revisão humana. */
@Service
class ReviewService {

    enum Verdict { APPROVE, DENY }

    record Review(UUID id, long amountCents, OffsetDateTime createdAt, String senderName, String recipientName,
            Integer score, JsonNode reasons) {
    }

    private final JdbcClient jdbc;
    private final PixSettlement settlement;
    private final JsonMapper json;
    private final Clock clock;

    ReviewService(JdbcClient jdbc, PixSettlement settlement, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.settlement = settlement;
        this.json = json;
        this.clock = clock;
    }

    List<Review> open() {
        return jdbc.sql("""
                        SELECT p.id, p.amount_cents, p.created_at, u.full_name, p.recipient_name, p.score, p.reasons::text AS reasons
                          FROM core.pix_transfer p
                          JOIN core.account a ON a.id = p.from_account
                          JOIN core.app_user u ON u.id = a.owner_id
                         WHERE p.status = 'UNDER_REVIEW'
                         ORDER BY p.created_at""")
                .query((rs, n) -> new Review(rs.getObject("id", UUID.class), rs.getLong("amount_cents"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getString("full_name"),
                        rs.getString("recipient_name"), rs.getObject("score", Integer.class),
                        rs.getString("reasons") == null ? json.createArrayNode() : json.readTree(rs.getString("reasons"))))
                .list();
    }

    /** Quem está dos dois lados do Pix não decide sobre ele. */
    @Transactional
    public void decide(UUID transferId, UUID analystId, Verdict verdict, String reason) {
        boolean involved = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM core.pix_transfer p
                                         JOIN core.account a ON a.id IN (p.from_account, p.to_account)
                                        WHERE p.id = :id AND a.owner_id = :analyst)""")
                .param("id", transferId).param("analyst", analystId).query(Boolean.class).single();
        if (involved) {
            throw new ApiException(HttpStatus.FORBIDDEN, "review.own", "Você está neste Pix. Outro analista precisa decidir.");
        }
        Outcome outcome = verdict == Verdict.APPROVE ? Outcome.SETTLED : Outcome.DENIED;
        boolean underReview = jdbc.sql("SELECT EXISTS (SELECT 1 FROM core.pix_transfer WHERE id = :id AND status = 'UNDER_REVIEW')")
                .param("id", transferId).query(Boolean.class).single();
        if (!underReview || !settlement.decide(transferId, outcome, null, null)) {
            throw ApiException.conflict("review.closed", "Este Pix não está mais esperando revisão.");
        }
        jdbc.sql("""
                        INSERT INTO core.pix_review (transfer_id, analyst_id, decision, reason, decided_at)
                        VALUES (:id, :analyst, :decision, :reason, :at)""")
                .param("id", transferId).param("analyst", analystId)
                .param("decision", verdict == Verdict.APPROVE ? "APPROVED" : "DENIED")
                .param("reason", reason.strip()).param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
    }
}
