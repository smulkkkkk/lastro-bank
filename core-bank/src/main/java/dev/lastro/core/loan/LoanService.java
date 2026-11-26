package dev.lastro.core.loan;

import dev.lastro.core.account.AccountService;
import dev.lastro.core.ledger.LedgerService;
import dev.lastro.core.ledger.LedgerService.Kind;
import dev.lastro.core.ledger.Posting;
import dev.lastro.core.loan.LoanMath.Part;
import dev.lastro.core.shared.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LoanService {

    static final long MIN_CENTS = 100_00;
    static final long MAX_CENTS = 5_000_00;
    static final int MIN_INSTALLMENTS = 3;
    static final int MAX_INSTALLMENTS = 24;
    static final BigDecimal MONTHLY_PERCENT = new BigDecimal("2.49");
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    enum Payment { NEXT, ALL }

    record Offer(long minCents, long maxCents, int minInstallments, int maxInstallments,
            BigDecimal monthlyPercent, BigDecimal yearPercent) {
    }

    record Quote(long amountCents, int installments, long installmentCents, long lastInstallmentCents,
            long interestCents, long totalCents, LocalDate firstDueOn) {
    }

    record Installment(int number, LocalDate dueOn, long principalCents, long interestCents, LocalDate paidOn,
            Long paidCents, boolean overdue) {
    }

    record Loan(UUID id, long principalCents, BigDecimal monthlyPercent, LocalDate createdOn, LocalDate settledOn,
            Long nextCents, Long settleCents, List<Installment> installments) {
    }

    record Overview(Offer offer, Loan active) {
    }

    private record Head(UUID id, long principalCents, BigDecimal monthlyPercent, LocalDate createdOn,
            LocalDate settledOn, int installments) {
    }

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final AccountService accounts;
    private final Clock clock;

    LoanService(JdbcClient jdbc, LedgerService ledger, AccountService accounts, Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.accounts = accounts;
        this.clock = clock;
    }

    Overview overview(UUID ownerId) {
        Offer offer = new Offer(MIN_CENTS, MAX_CENTS, MIN_INSTALLMENTS, MAX_INSTALLMENTS, MONTHLY_PERCENT,
                LoanMath.yearPercent(MONTHLY_PERCENT));
        Loan active = head("l.owner_id = :value AND l.settled_on IS NULL", ownerId).map(this::view).orElse(null);
        return new Overview(offer, active);
    }

    Quote quote(long cents, int installments) {
        List<Part> parts = LoanMath.schedule(cents, MONTHLY_PERCENT, installments);
        long interest = parts.stream().mapToLong(Part::interestCents).sum();
        return new Quote(cents, installments, parts.getFirst().totalCents(), parts.getLast().totalCents(), interest,
                cents + interest, today().plusMonths(1));
    }

    /** Só contrata pelo total que a pessoa viu. */
    @Transactional
    public Loan take(UUID ownerId, long cents, int installments, long acceptedTotalCents, String idempotencyKey) {
        UUID checking = accounts.checkingId(ownerId);
        ledger.lock(List.of(checking));
        Optional<Head> previous = jdbc.sql("SELECT account_id FROM core.loan WHERE owner_id = :owner AND idempotency_key = :key")
                .param("owner", ownerId).param("key", idempotencyKey).query(UUID.class).optional()
                .flatMap(id -> head("l.account_id = :value", id));
        if (previous.isPresent()) {
            if (previous.get().principalCents() != cents || previous.get().installments() != installments) {
                throw mismatch();
            }
            return view(previous.get());
        }
        if (head("l.owner_id = :value AND l.settled_on IS NULL", ownerId).isPresent()) {
            throw ApiException.unprocessable("loan.active", "Quite o empréstimo atual antes de pedir outro.");
        }
        Quote quote = quote(cents, installments);
        if (quote.totalCents() != acceptedTotalCents) {
            throw ApiException.conflict("loan.quote_changed", "O custo mudou. Confira o novo total.");
        }
        UUID loanId = accounts.openLoan(ownerId, cents);
        LocalDate today = today();
        jdbc.sql("""
                        INSERT INTO core.loan (account_id, owner_id, principal_cents, monthly_percent, installments,
                                               idempotency_key, created_on)
                        VALUES (:id, :owner, :cents, :rate, :n, :key, :today)""")
                .param("id", loanId).param("owner", ownerId).param("cents", cents).param("rate", MONTHLY_PERCENT)
                .param("n", installments).param("key", idempotencyKey).param("today", today)
                .update();
        List<Part> parts = LoanMath.schedule(cents, MONTHLY_PERCENT, installments);
        for (int i = 0; i < parts.size(); i++) {
            jdbc.sql("""
                            INSERT INTO core.loan_installment (loan_id, number, due_on, principal_cents, interest_cents)
                            VALUES (:id, :number, :due, :principal, :interest)""")
                    .param("id", loanId).param("number", i + 1).param("due", today.plusMonths(i + 1))
                    .param("principal", parts.get(i).principalCents()).param("interest", parts.get(i).interestCents())
                    .update();
        }
        ledger.post(Kind.LOAN, "Empréstimo", "loan:" + loanId, List.of(
                Posting.debit(loanId, cents), Posting.credit(checking, cents)));
        return view(head("l.account_id = :value", loanId).orElseThrow());
    }

    @Transactional
    public Loan pay(UUID loanId, UUID ownerId, Payment kind, long expectedCents, String idempotencyKey) {
        owned(loanId, ownerId);
        UUID checking = accounts.checkingId(ownerId);
        ledger.lock(List.of(checking, loanId, LedgerService.INTEREST_ACCOUNT));
        String ref = "loan:" + loanId + ":" + kind + ":" + idempotencyKey;
        Optional<Long> previous = ledger.amountByRef(ref, checking);
        if (previous.isPresent()) {
            if (previous.get() != expectedCents) {
                throw mismatch();
            }
            return view(owned(loanId, ownerId));
        }
        List<Installment> open = installments(loanId).stream().filter(i -> i.paidOn() == null).toList();
        if (open.isEmpty()) {
            throw ApiException.unprocessable("loan.settled", "Este empréstimo já foi quitado.");
        }
        List<Installment> paying = kind == Payment.NEXT ? List.of(open.getFirst()) : open;
        long principal = paying.stream().mapToLong(Installment::principalCents).sum();
        long interest = kind == Payment.NEXT ? open.getFirst().interestCents() : interestToSettle(open);
        if (principal + interest != expectedCents) {
            throw ApiException.conflict("loan.amount_changed", "O valor mudou. Confira e confirme de novo.");
        }

        List<Posting> postings = new ArrayList<>(List.of(
                Posting.debit(checking, expectedCents), Posting.credit(loanId, principal)));
        if (interest > 0) {
            postings.add(Posting.credit(LedgerService.INTEREST_ACCOUNT, interest));
        }
        ledger.post(Kind.LOAN_PAYMENT,
                kind == Payment.NEXT ? "Parcela do empréstimo" : "Quitação do empréstimo", ref, postings);
        LocalDate today = today();
        for (Installment item : paying) {
            boolean charged = kind == Payment.NEXT || chargesInterest(item, open);
            jdbc.sql("""
                            UPDATE core.loan_installment SET paid_on = :today, paid_cents = :cents
                             WHERE loan_id = :id AND number = :number""")
                    .param("today", today)
                    .param("cents", item.principalCents() + (charged ? item.interestCents() : 0))
                    .param("id", loanId).param("number", item.number())
                    .update();
        }
        if (paying.size() == open.size()) {
            jdbc.sql("UPDATE core.loan SET settled_on = :today WHERE account_id = :id")
                    .param("today", today).param("id", loanId).update();
        }
        return view(owned(loanId, ownerId));
    }

    /** Quitar paga os juros das vencidas e da próxima; os dos meses que não vão acontecer saem. */
    private long interestToSettle(List<Installment> open) {
        return open.stream().filter(item -> chargesInterest(item, open)).mapToLong(Installment::interestCents).sum();
    }

    private static boolean chargesInterest(Installment item, List<Installment> open) {
        return item.overdue() || item.number() == open.stream().filter(i -> !i.overdue()).mapToInt(Installment::number)
                .min().orElse(-1);
    }

    private Loan view(Head head) {
        List<Installment> all = installments(head.id());
        List<Installment> open = all.stream().filter(i -> i.paidOn() == null).toList();
        Long next = open.isEmpty() ? null : open.getFirst().principalCents() + open.getFirst().interestCents();
        Long settle = open.isEmpty() ? null
                : open.stream().mapToLong(Installment::principalCents).sum() + interestToSettle(open);
        return new Loan(head.id(), head.principalCents(), head.monthlyPercent(), head.createdOn(), head.settledOn(),
                next, settle, all);
    }

    private List<Installment> installments(UUID loanId) {
        LocalDate today = today();
        return jdbc.sql("SELECT * FROM core.loan_installment WHERE loan_id = :id ORDER BY number")
                .param("id", loanId)
                .query((rs, n) -> {
                    LocalDate due = rs.getObject("due_on", LocalDate.class);
                    LocalDate paid = rs.getObject("paid_on", LocalDate.class);
                    return new Installment(rs.getInt("number"), due, rs.getLong("principal_cents"),
                            rs.getLong("interest_cents"), paid, rs.getObject("paid_cents", Long.class),
                            paid == null && due.isBefore(today));
                })
                .list();
    }

    private Head owned(UUID loanId, UUID ownerId) {
        return jdbc.sql("SELECT account_id FROM core.loan WHERE account_id = :id AND owner_id = :owner")
                .param("id", loanId).param("owner", ownerId).query(UUID.class).optional()
                .flatMap(id -> head("l.account_id = :value", id))
                .orElseThrow(() -> ApiException.notFound("loan.not_found", "Empréstimo não encontrado."));
    }

    private Optional<Head> head(String condition, UUID value) {
        return jdbc.sql("SELECT * FROM core.loan l WHERE " + condition)
                .param("value", value)
                .query((rs, n) -> new Head(rs.getObject("account_id", UUID.class), rs.getLong("principal_cents"),
                        rs.getBigDecimal("monthly_percent").stripTrailingZeros(),
                        rs.getObject("created_on", LocalDate.class), rs.getObject("settled_on", LocalDate.class),
                        rs.getInt("installments")))
                .optional();
    }

    private static ApiException mismatch() {
        return ApiException.conflict("idempotency.mismatch", "Esta Idempotency-Key já foi usada em outro pedido.");
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SAO_PAULO));
    }
}
