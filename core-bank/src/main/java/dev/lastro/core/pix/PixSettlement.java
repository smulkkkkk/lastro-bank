package dev.lastro.core.pix;

import dev.lastro.core.ledger.LedgerService;
import dev.lastro.core.ledger.LedgerService.Kind;
import dev.lastro.core.ledger.Posting;
import dev.lastro.core.notify.Notifier;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O único lugar que tira um Pix do estado de espera. Toda decisão confere o estado atual com a
 * transferência travada: uma resposta atrasada do sentinel não ressuscita um Pix já cancelado.
 */
@Service
class PixSettlement {

    enum Outcome { SETTLED, DENIED, UNDER_REVIEW, CANCELLED }

    private record Open(UUID from, UUID to, long amountCents, String status, String senderName, String recipientName,
            UUID senderId, UUID recipientId) {
    }

    private static final Set<String> WAITING = Set.of("PENDING", "UNDER_REVIEW");

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final Notifier notifier;
    private final Clock clock;

    PixSettlement(JdbcClient jdbc, LedgerService ledger, Notifier notifier, Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** Devolve false quando o Pix já não estava esperando aquela decisão. */
    @Transactional
    public boolean decide(UUID transferId, Outcome outcome, Integer score, String reasonsJson) {
        Optional<Open> peek = load(transferId, false);
        if (peek.isEmpty()) {
            return false;
        }
        ledger.lock(List.of(peek.get().from(), peek.get().to()));
        Open open = load(transferId, true).orElseThrow();
        boolean allowed = outcome == Outcome.UNDER_REVIEW ? open.status().equals("PENDING") : WAITING.contains(open.status());
        if (!allowed) {
            dropQueue(transferId);
            return false;
        }

        UUID transactionId = null;
        if (outcome != Outcome.UNDER_REVIEW) {
            jdbc.sql("UPDATE core.account SET held_cents = held_cents - :cents WHERE id = :id")
                    .param("cents", open.amountCents()).param("id", open.from()).update();
        }
        if (outcome == Outcome.SETTLED) {
            transactionId = ledger.post(Kind.TRANSFER, "Pix", null, List.of(
                    Posting.debit(open.from(), open.amountCents(), "Pix para " + open.recipientName()),
                    Posting.credit(open.to(), open.amountCents(), "Pix de " + open.senderName())));
        }
        jdbc.sql("""
                        UPDATE core.pix_transfer
                           SET status = :status, transaction_id = :tx, decided_at = :now,
                               score = coalesce(CAST(:score AS smallint), score),
                               reasons = coalesce(CAST(:reasons AS jsonb), reasons)
                         WHERE id = :id""")
                .param("status", outcome.name()).param("tx", transactionId).param("now", now())
                .param("score", score).param("reasons", reasonsJson).param("id", transferId)
                .update();
        dropQueue(transferId);
        announce(open, outcome);
        return true;
    }

    /** Os avisos entram na mesma transação da decisão: só saem se ela confirmar. */
    private void announce(Open open, Outcome outcome) {
        String amount = Notifier.money(open.amountCents());
        String to = amount + " para " + open.recipientName();
        switch (outcome) {
            case SETTLED -> {
                notifier.send(open.senderId(), Notifier.Kind.PIX_SENT, "Pix enviado", to, "/app/extrato");
                notifier.send(open.recipientId(), Notifier.Kind.PIX_RECEIVED, "Pix recebido",
                        amount + " de " + open.senderName(), "/app/extrato");
            }
            case UNDER_REVIEW -> notifier.send(open.senderId(), Notifier.Kind.PIX_REVIEW, "Pix em revisão",
                    to + ". Uma pessoa vai conferir; o valor fica reservado até a decisão.", "/app/pix");
            case DENIED -> notifier.send(open.senderId(), Notifier.Kind.PIX_DENIED, "Pix não enviado",
                    to + " foi barrado pela análise de segurança. O valor voltou para o seu saldo.", "/app/extrato");
            case CANCELLED -> notifier.send(open.senderId(), Notifier.Kind.PIX_CANCELLED, "Pix cancelado",
                    to + ": a análise não terminou a tempo. O valor voltou para o seu saldo.", "/app/extrato");
        }
    }

    private void dropQueue(UUID transferId) {
        jdbc.sql("DELETE FROM core.outbox WHERE kind = 'PIX_ANALYSIS' AND aggregate_id = :id")
                .param("id", transferId).update();
    }

    private Optional<Open> load(UUID transferId, boolean lock) {
        return jdbc.sql("""
                        SELECT p.from_account, p.to_account, p.amount_cents, p.status, p.recipient_name, u.full_name,
                               a.owner_id AS sender_id, b.owner_id AS recipient_id
                          FROM core.pix_transfer p
                          JOIN core.account a ON a.id = p.from_account
                          JOIN core.account b ON b.id = p.to_account
                          JOIN core.app_user u ON u.id = a.owner_id
                         WHERE p.id = :id\s""" + (lock ? "FOR UPDATE OF p" : ""))
                .param("id", transferId)
                .query((rs, n) -> new Open(rs.getObject("from_account", UUID.class), rs.getObject("to_account", UUID.class),
                        rs.getLong("amount_cents"), rs.getString("status"), rs.getString("full_name"),
                        rs.getString("recipient_name"), rs.getObject("sender_id", UUID.class),
                        rs.getObject("recipient_id", UUID.class)))
                .optional();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
