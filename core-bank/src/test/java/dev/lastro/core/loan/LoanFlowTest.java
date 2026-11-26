package dev.lastro.core.loan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lastro.core.support.ApiClient;
import dev.lastro.core.support.IntegrationTest;
import dev.lastro.core.support.TestClock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

class LoanFlowTest extends IntegrationTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 31);

    private Customer ana;
    private ApiClient api;

    @BeforeEach
    void setUp() throws Exception {
        ana = newCustomer();
        api = new ApiClient(mvc, ana.bearer());
        dayIs(START);
    }

    @Test
    void takingALoanPutsMoneyInTheAccountAndDebtInTheLedger() throws Exception {
        JsonNode loan = take(1_000_00, 12);
        assertThat(checking()).isEqualTo(2_000_00);
        assertThat(debt(loan)).isEqualTo(-1_000_00);
        assertThat(loan.get("installments").size()).isEqualTo(12);
        assertThat(loan.get("installments").get(0).get("dueOn").asString()).as("fevereiro").isEqualTo("2026-02-28");
        assertThat(loan.get("installments").get(1).get("dueOn").asString()).isEqualTo("2026-03-31");
        assertThat(loan.get("nextCents").asLong()).isEqualTo(97_43);
    }

    @Test
    void payingEveryInstallmentSettlesItExactly() throws Exception {
        JsonNode loan = take(1_000_00, 12);
        long total = quote(1_000_00, 12);
        for (int i = 0; i < 12; i++) {
            JsonNode active = active();
            assertThat(pay(active, "NEXT", active.get("nextCents").asLong()).getStatus()).isEqualTo(200);
        }
        assertThat(active()).isNull();
        assertThat(debt(loan)).isZero();
        assertThat(checking()).isEqualTo(2_000_00 - total);
        assertThat(code(pay(loan, "NEXT", 1))).isEqualTo("loan.settled");
        assertThat(takeRaw(500_00, 6, quote(500_00, 6), key()).getStatus()).as("pode pedir outro").isEqualTo(201);
    }

    @Test
    void settlingEarlyRemovesFutureInterest() throws Exception {
        JsonNode loan = take(1_000_00, 12);
        long settle = active().get("settleCents").asLong();
        assertThat(settle).as("principal + juros só da próxima").isEqualTo(1_000_00 + 24_90);
        assertThat(pay(loan, "ALL", settle).getStatus()).isEqualTo(200);
        assertThat(debt(loan)).isZero();
        assertThat(checking()).isEqualTo(2_000_00 - settle);
        assertThat(active()).isNull();
    }

    @Test
    void overdueInstallmentsKeepTheirInterestWhenSettling() throws Exception {
        JsonNode loan = take(1_000_00, 12);
        dayIs(START.plusMonths(2).plusDays(1));
        JsonNode installments = active().get("installments");
        assertThat(installments.get(0).get("overdue").asBoolean()).isTrue();
        assertThat(installments.get(1).get("overdue").asBoolean()).isTrue();
        assertThat(installments.get(2).get("overdue").asBoolean()).isFalse();
        long interest = 0;
        for (int i = 0; i < 3; i++) {
            interest += installments.get(i).get("interestCents").asLong();
        }
        assertThat(active().get("settleCents").asLong()).isEqualTo(1_000_00 + interest);
        assertThat(pay(loan, "ALL", 1_000_00 + interest).getStatus()).isEqualTo(200);
        long paid = jdbc.sql("SELECT sum(paid_cents) FROM core.loan_installment WHERE loan_id = :id")
                .param("id", id(loan)).query(Long.class).single();
        assertThat(paid).isEqualTo(1_000_00 + interest);
    }

    @Test
    void onlyOneActiveLoanEvenUnderConcurrency() throws Exception {
        long total = quote(500_00, 6);
        List<Integer> statuses = contend(ana.accountId(), 8, () -> takeRaw(500_00, 6, total, key()).getStatus());
        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 422)).hasSize(7);
        assertThat(checking()).isEqualTo(1_500_00);
    }

    @Test
    void repeatedRequestTakesOneLoan() throws Exception {
        long total = quote(500_00, 6);
        String key = key();
        List<JsonNode> loans = contend(ana.accountId(), 8, () -> body(takeRaw(500_00, 6, total, key)));
        assertThat(loans).allMatch(loan -> loan.has("id"));
        assertThat(new HashSet<>(loans)).hasSize(1);
        assertThat(code(takeRaw(600_00, 6, quote(600_00, 6), key))).isEqualTo("idempotency.mismatch");
    }

    @Test
    void repeatedPaymentIsChargedOnce() throws Exception {
        JsonNode loan = take(1_000_00, 12);
        String key = key();
        List<Integer> statuses = contend(ana.accountId(), 8, () -> payRaw(loan, "NEXT", 97_43, key).getStatus());
        assertThat(statuses).allMatch(s -> s == 200);
        assertThat(checking()).isEqualTo(2_000_00 - 97_43);
    }

    @Test
    void onlyAcceptsTheCostThatWasShown() throws Exception {
        assertThat(code(takeRaw(1_000_00, 12, quote(1_000_00, 12) - 1, key()))).isEqualTo("loan.quote_changed");
        assertThat(checking()).isEqualTo(1_000_00);
        JsonNode loan = take(1_000_00, 12);
        assertThat(code(pay(loan, "NEXT", 97_42))).isEqualTo("loan.amount_changed");
        assertThat(code(pay(loan, "ALL", 97_43))).isEqualTo("loan.amount_changed");
    }

    @Test
    void limitsAndOwnershipAreEnforced() throws Exception {
        for (String body : new String[] {"99_99,12", "500001,12", "100000,2", "100000,25"}) {
            String[] parts = body.replace("_", "").split(",");
            assertThat(takeRaw(Long.parseLong(parts[0]), Integer.parseInt(parts[1]), 1, key()).getStatus())
                    .as(body).isEqualTo(400);
        }
        JsonNode loan = take(1_000_00, 12);
        Customer other = newCustomer();
        api = new ApiClient(mvc, other.bearer());
        assertThat(code(pay(loan, "NEXT", 97_43))).isEqualTo("loan.not_found");
        assertThat(active()).isNull();
    }

    private void dayIs(LocalDate day) {
        clock.set(day.atTime(LocalTime.NOON).atZone(TestClock.SAO_PAULO));
    }

    private long quote(long cents, int installments) throws Exception {
        return body(api.read("/loans/quote?amountCents=" + cents + "&installments=" + installments))
                .get("totalCents").asLong();
    }

    private JsonNode take(long cents, int installments) throws Exception {
        MockHttpServletResponse response = takeRaw(cents, installments, quote(cents, installments), key());
        assertThat(response.getStatus()).isEqualTo(201);
        return body(response);
    }

    private MockHttpServletResponse takeRaw(long cents, int installments, long accepted, String key) throws Exception {
        return api.send("/loans", "{\"amountCents\": %d, \"installments\": %d, \"acceptedTotalCents\": %d}"
                .formatted(cents, installments, accepted), key);
    }

    private JsonNode active() throws Exception {
        JsonNode active = body(api.read("/loans")).get("active");
        return active.isNull() ? null : active;
    }

    private MockHttpServletResponse pay(JsonNode loan, String kind, long cents) throws Exception {
        return payRaw(loan, kind, cents, key());
    }

    private MockHttpServletResponse payRaw(JsonNode loan, String kind, long cents, String key) throws Exception {
        return api.send("/loans/" + id(loan) + "/payments",
                "{\"kind\": \"%s\", \"expectedCents\": %d}".formatted(kind, cents), key);
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return body(response).get("code").asString();
    }

    private static UUID id(JsonNode loan) {
        return UUID.fromString(loan.get("id").asString());
    }

    private long debt(JsonNode loan) {
        return balance(id(loan));
    }

    private long checking() {
        return balance(ana.accountId());
    }

    private long balance(UUID account) {
        return jdbc.sql("SELECT balance_cents FROM core.account WHERE id = :id")
                .param("id", account).query(Long.class).single();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
