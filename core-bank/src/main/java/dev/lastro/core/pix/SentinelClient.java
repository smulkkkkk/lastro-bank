package dev.lastro.core.pix;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Quem analisa o risco. O formato está em contracts/, com testes dos dois lados. */
public interface SentinelClient {

    record AnalysisRequest(UUID transferId, UUID accountId, UUID recipientAccountId, long amountCents,
            Instant accountCreatedAt, Instant requestedAt) {
    }

    record Reason(String code, int points, String text) {
    }

    enum Decision { APPROVED, REVIEW, DENIED }

    record Analysis(UUID transferId, int score, Decision decision, List<Reason> reasons) {
    }

    /** Lança exceção se o sentinel não responder; quem chama tenta de novo depois. */
    Analysis analyze(AnalysisRequest request);
}
