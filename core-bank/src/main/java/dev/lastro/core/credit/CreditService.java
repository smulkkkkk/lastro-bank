package dev.lastro.core.credit;

import dev.lastro.core.account.AccountService;
import dev.lastro.core.ledger.LedgerService;
import dev.lastro.core.ledger.LedgerService.Kind;
import dev.lastro.core.ledger.Posting;
import dev.lastro.core.reward.RewardService;
import dev.lastro.core.shared.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CreditService {

    static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    static final long MIN_INSTALLMENT_CENTS = 5_00;
    static final long MIN_CONVERSION_CENTS = 50_00;
    static final int MAX_CONVERSION_INSTALLMENTS = 6;
    private static final int CONVERSION_FEE_BP = 450;
    private static final int EXTRA_INSTALLMENT_FEE_BP = 250;

    enum Mode { DEBIT, CREDIT }

    record Purchase(UUID id, UUID cardId, String kind, String mode, String merchant, String category,
            long amountCents, int installments, boolean contactless, OffsetDateTime createdAt, long discountCents) {
    }

    record Invoice(LocalDate reference, LocalDate closesOn, LocalDate dueOn, String status, long totalCents,
            long paidCents, long remainingCents) {
    }

    record InvoiceItem(UUID purchaseId, String merchant, String category, int number, int installments,
            long amountCents, OffsetDateTime createdAt) {
    }

    record Overview(long limitCents, long usedCents, long availableCents, boolean blocked, List<Invoice> invoices) {
    }

    record Quote(long amountCents, int installments, long feeCents, long totalCents, long firstInstallmentCents,
            long otherInstallmentsCents) {
    }

    private static final RowMapper<Purchase> PURCHASE = (rs, n) -> new Purchase(rs.getObject("id", UUID.class),
            rs.getObject("card_id", UUID.class), rs.getString("kind"), rs.getString("mode"),
            rs.getString("merchant"), rs.getString("category"), rs.getLong("amount_cents"),
            rs.getInt("installments"), rs.getBoolean("contactless"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getLong("discount_cents"));

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final AccountService accounts;
    private final Clock clock;

    private final RewardService rewards;

    CreditService(JdbcClient jdbc, LedgerService ledger, AccountService accounts, RewardService rewards, Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.accounts = accounts;
        this.rewards = rewards;
        this.clock = clock;
    }

    Overview overview(UUID ownerId) {
        LocalDate today = today();
        long[] account = jdbc.sql("""
                        SELECT credit_limit_cents, balance_cents FROM core.account
                         WHERE owner_id = :owner AND kind = 'CREDIT'""")
                .param("owner", ownerId)
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
        List<Invoice> invoices = invoices(ownerId, today);
        boolean blocked = invoices.stream().anyMatch(invoice -> invoice.status().equals("OVERDUE"));
        return new Overview(account[0], -account[1], account[0] + account[1], blocked, invoices);
    }

    List<InvoiceItem> items(UUID ownerId, LocalDate reference) {
        return jdbc.sql("""
                        SELECT p.id, p.merchant, p.category, i.number, p.installments, i.amount_cents, p.created_at
                          FROM core.installment i JOIN core.purchase p ON p.id = i.purchase_id
                         WHERE i.owner_id = :owner AND i.reference = :reference
                         ORDER BY p.created_at DESC, i.number""")
                .param("owner", ownerId).param("reference", reference)
                .query((rs, n) -> new InvoiceItem(rs.getObject("id", UUID.class), rs.getString("merchant"),
                        rs.getString("category"), rs.getInt("number"), rs.getInt("installments"),
                        rs.getLong("amount_cents"), rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    List<Purchase> purchases(UUID ownerId) {
        return jdbc.sql("SELECT * FROM core.purchase WHERE owner_id = :owner ORDER BY created_at DESC, id LIMIT 50")
                .param("owner", ownerId).query(PURCHASE).list();
    }

    @Transactional
    public Purchase buy(UUID ownerId, UUID cardId, Merchant merchant, long cents, Mode mode, int installments,
            boolean contactless, String idempotencyKey) {
        UUID checking = accounts.checkingId(ownerId);
        UUID credit = accounts.creditId(ownerId);
        ledger.lock(List.of(checking, credit));

        Optional<Purchase> previous = find(ownerId, idempotencyKey);
        if (previous.isPresent()) {
            Purchase p = previous.get();
            if (!cardId.equals(p.cardId()) || !p.merchant().equals(merchant.title())
                    || p.amountCents() + p.discountCents() != cents
                    || !p.mode().equals(mode.name()) || p.installments() != installments
                    || p.contactless() != contactless) {
                throw mismatch();
            }
            return p;
        }
        boolean[] card = jdbc.sql("""
                        SELECT c.frozen, c.contactless FROM core.card c
                         WHERE c.id = :id AND c.account_id = :account FOR SHARE""")
                .param("id", cardId).param("account", checking)
                .query((rs, n) -> new boolean[] {rs.getBoolean(1), rs.getBoolean(2)}).optional()
                .orElseThrow(() -> ApiException.notFound("card.not_found", "Cartão não encontrado."));
        if (card[0]) {
            throw ApiException.unprocessable("card.frozen", "Este cartão está bloqueado.");
        }
        if (contactless && !card[1]) {
            throw ApiException.unprocessable("card.contactless_off", "A aproximação deste cartão está desligada.");
        }
        if (mode == Mode.DEBIT && installments != 1) {
            throw ApiException.unprocessable("purchase.installments", "Compra no débito é à vista.");
        }
        // Cupom da loja, se houver: a compra é cobrada já com o desconto.
        Optional<RewardService.UsableCoupon> coupon = rewards.usableCoupon(ownerId, merchant.name(), cents);
        long discount = coupon.map(RewardService.UsableCoupon::discountCents).orElse(0L);
        long charged = cents - discount;
        if (charged / installments < MIN_INSTALLMENT_CENTS && installments > 1) {
            throw ApiException.unprocessable("purchase.small_installment", "Cada parcela precisa ter pelo menos R$ 5,00.");
        }
        if (mode == Mode.CREDIT) {
            rejectOverdue(ownerId);
        }
        UUID transactionId = ledger.post(Kind.CARD_PURCHASE, merchant.title(), null, List.of(
                Posting.debit(mode == Mode.DEBIT ? checking : credit, charged),
                Posting.credit(LedgerService.MERCHANTS_ACCOUNT, charged)));
        coupon.ifPresent(used -> rewards.markUsed(used.id(), transactionId));
        Purchase purchase = save(ownerId, cardId, "PURCHASE", mode, merchant.title(), merchant.category(), charged,
                discount, installments, contactless, idempotencyKey, transactionId);
        rewards.earn(ownerId, merchant.name(), charged, purchase.id(), merchant.title());
        return purchase;
    }

    @Transactional
    public Overview pay(UUID ownerId, LocalDate reference, long cents, String idempotencyKey) {
        UUID checking = accounts.checkingId(ownerId);
        UUID credit = accounts.creditId(ownerId);
        ledger.lock(List.of(checking, credit));
        String ref = "invoice:" + ownerId + ":" + reference + ":" + idempotencyKey;
        Optional<Long> previous = ledger.amountByRef(ref, checking);
        if (previous.isPresent()) {
            if (previous.get() != cents) {
                throw mismatch();
            }
            return overview(ownerId);
        }
        long remaining = invoices(ownerId, today()).stream()
                .filter(invoice -> invoice.reference().equals(reference))
                .mapToLong(Invoice::remainingCents).sum();
        if (cents > remaining) {
            throw ApiException.unprocessable("invoice.overpaid", "O valor passa do que falta nesta fatura.");
        }
        UUID transactionId = ledger.post(Kind.INVOICE_PAYMENT, "Pagamento de fatura", ref, List.of(
                Posting.debit(checking, cents), Posting.credit(credit, cents)));
        jdbc.sql("""
                        INSERT INTO core.invoice_payment (id, owner_id, reference, amount_cents, transaction_id, created_at)
                        VALUES (:id, :owner, :reference, :cents, :tx, :at)""")
                .param("id", UUID.randomUUID()).param("owner", ownerId).param("reference", reference)
                .param("cents", cents).param("tx", transactionId).param("at", now())
                .update();
        return overview(ownerId);
    }

    Quote quote(long cents, int installments) {
        long basisPoints = CONVERSION_FEE_BP + (long) EXTRA_INSTALLMENT_FEE_BP * (installments - 1);
        long fee = (cents * basisPoints + 5_000) / 10_000;
        List<Long> parts = InvoiceCalendar.split(cents + fee, installments);
        return new Quote(cents, installments, fee, cents + fee, parts.getFirst(), parts.getLast());
    }

    /** Vira limite em saldo. Só vale pelo total que a pessoa viu: se a taxa mudou, ela confirma de novo. */
    @Transactional
    public Purchase convert(UUID ownerId, long cents, int installments, long acceptedTotalCents, String idempotencyKey) {
        UUID checking = accounts.checkingId(ownerId);
        UUID credit = accounts.creditId(ownerId);
        ledger.lock(List.of(checking, credit, LedgerService.INTEREST_ACCOUNT));
        Optional<Purchase> previous = find(ownerId, idempotencyKey);
        if (previous.isPresent()) {
            Purchase p = previous.get();
            if (!p.kind().equals("CONVERSION") || p.amountCents() != acceptedTotalCents
                    || p.installments() != installments) {
                throw mismatch();
            }
            return p;
        }
        Quote quote = quote(cents, installments);
        if (quote.totalCents() != acceptedTotalCents) {
            throw ApiException.conflict("credit.quote_changed", "O custo mudou. Confira o novo total.");
        }
        rejectOverdue(ownerId);
        UUID transactionId = ledger.post(Kind.CREDIT_CONVERSION, "Limite convertido em saldo", null, List.of(
                Posting.debit(credit, quote.totalCents()),
                Posting.credit(checking, cents),
                Posting.credit(LedgerService.INTEREST_ACCOUNT, quote.feeCents())));
        return save(ownerId, null, "CONVERSION", Mode.CREDIT, "Limite convertido em saldo", "Crédito",
                quote.totalCents(), 0, installments, false, idempotencyKey, transactionId);
    }

    private Purchase save(UUID ownerId, UUID cardId, String kind, Mode mode, String merchant, String category,
            long cents, long discount, int installments, boolean contactless, String idempotencyKey, UUID transactionId) {
        UUID id = UUID.randomUUID();
        Purchase purchase = jdbc.sql("""
                        INSERT INTO core.purchase (id, owner_id, card_id, kind, mode, merchant, category, amount_cents,
                                                   discount_cents, installments, contactless, idempotency_key,
                                                   transaction_id, created_at)
                        VALUES (:id, :owner, :card, :kind, :mode, :merchant, :category, :cents,
                                :discount, :installments, :contactless, :key, :tx, :at)
                        RETURNING *""")
                .param("id", id).param("owner", ownerId).param("card", cardId).param("kind", kind)
                .param("mode", mode.name()).param("merchant", merchant).param("category", category)
                .param("cents", cents).param("discount", discount).param("installments", installments).param("contactless", contactless)
                .param("key", idempotencyKey).param("tx", transactionId).param("at", now())
                .query(PURCHASE).single();
        if (mode == Mode.CREDIT) {
            LocalDate first = InvoiceCalendar.reference(today());
            List<Long> parts = InvoiceCalendar.split(cents, installments);
            for (int i = 0; i < parts.size(); i++) {
                jdbc.sql("""
                                INSERT INTO core.installment (purchase_id, number, owner_id, reference, amount_cents)
                                VALUES (:id, :number, :owner, :reference, :cents)""")
                        .param("id", id).param("number", i + 1).param("owner", ownerId)
                        .param("reference", first.plusMonths(i)).param("cents", parts.get(i))
                        .update();
            }
        }
        return purchase;
    }

    private List<Invoice> invoices(UUID ownerId, LocalDate today) {
        LocalDate open = InvoiceCalendar.reference(today);
        return jdbc.sql("""
                        WITH months AS (
                            SELECT reference, sum(amount_cents) AS total FROM core.installment
                             WHERE owner_id = :owner GROUP BY reference
                             UNION ALL SELECT CAST(:open AS date), 0)
                        SELECT m.reference, sum(m.total)::bigint AS total,
                               (SELECT coalesce(sum(p.amount_cents), 0) FROM core.invoice_payment p
                                 WHERE p.owner_id = :owner AND p.reference = m.reference)::bigint AS paid
                          FROM months m GROUP BY m.reference ORDER BY m.reference""")
                .param("owner", ownerId).param("open", open)
                .query((rs, n) -> {
                    LocalDate reference = rs.getObject("reference", LocalDate.class);
                    long total = rs.getLong("total");
                    long paid = rs.getLong("paid");
                    return new Invoice(reference, InvoiceCalendar.closesOn(reference), InvoiceCalendar.dueOn(reference),
                            status(reference, open, today, total - paid), total, paid, total - paid);
                })
                .list();
    }

    private static String status(LocalDate reference, LocalDate open, LocalDate today, long remaining) {
        if (reference.equals(open)) {
            return "OPEN";
        }
        if (reference.isAfter(open)) {
            return "FUTURE";
        }
        if (remaining == 0) {
            return "PAID";
        }
        return today.isAfter(InvoiceCalendar.dueOn(reference)) ? "OVERDUE" : "CLOSED";
    }

    private void rejectOverdue(UUID ownerId) {
        if (invoices(ownerId, today()).stream().anyMatch(invoice -> invoice.status().equals("OVERDUE"))) {
            throw ApiException.unprocessable("credit.blocked", "Há fatura vencida. Pague para voltar a usar o crédito.");
        }
    }

    private Optional<Purchase> find(UUID ownerId, String idempotencyKey) {
        return jdbc.sql("SELECT * FROM core.purchase WHERE owner_id = :owner AND idempotency_key = :key")
                .param("owner", ownerId).param("key", idempotencyKey).query(PURCHASE).optional();
    }

    private static ApiException mismatch() {
        return ApiException.conflict("idempotency.mismatch", "Esta Idempotency-Key já foi usada em outro pedido.");
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SAO_PAULO));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
