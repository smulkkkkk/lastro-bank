package dev.lastro.core.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

class CreditFlowTest extends IntegrationTest {

    private Customer ana;
    private ApiClient api;
    private String card;

    @BeforeEach
    void setUp() throws Exception {
        ana = newCustomer();
        api = new ApiClient(mvc, ana.bearer());
        card = body(api.read("/cards")).get(0).get("id").asString();
        dayIs(LocalDate.of(2026, 11, 10));
    }

    @Test
    void debitTakesFromTheAccountAndCreditFromTheLimit() throws Exception {
        assertThat(buy(150_00, "DEBIT", 1).getStatus()).isEqualTo(200);
        assertThat(checking()).isEqualTo(850_00);
        assertThat(credit().get("usedCents").asLong()).isZero();

        assertThat(buy(200_00, "CREDIT", 1).getStatus()).isEqualTo(200);
        assertThat(checking()).isEqualTo(850_00);
        assertThat(credit().get("usedCents").asLong()).isEqualTo(200_00);
        assertThat(credit().get("availableCents").asLong()).isEqualTo(2_800_00);
        assertThat(body(api.read("/purchases")).size()).isEqualTo(2);
    }

    @Test
    void installmentsFallInConsecutiveInvoicesAndUseTheWholeLimit() throws Exception {
        buy(100_00, "CREDIT", 3);
        assertThat(invoice("2026-12").get("totalCents").asLong()).isEqualTo(33_34);
        assertThat(invoice("2027-01").get("totalCents").asLong()).isEqualTo(33_33);
        assertThat(invoice("2027-02").get("totalCents").asLong()).isEqualTo(33_33);
        assertThat(credit().get("usedCents").asLong()).isEqualTo(100_00);
        JsonNode item = body(api.read("/credit/invoices/2027-01/items")).get(0);
        assertThat(item.get("number").asInt()).isEqualTo(2);
        assertThat(item.get("installments").asInt()).isEqualTo(3);
    }

    @Test
    void limitIsNotPassedUnderConcurrency() throws Exception {
        List<Integer> statuses = contend(creditAccount(), 8, () -> buy(800_00, "CREDIT", 1).getStatus());
        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(3);
        assertThat(credit().get("usedCents").asLong()).isEqualTo(2_400_00);
        assertThat(code(buy(600_01, "CREDIT", 1))).isEqualTo("credit.limit");
        assertThat(buy(600_00, "CREDIT", 1).getStatus()).isEqualTo(200);
    }

    @Test
    void theDatabaseItselfRefusesToPassTheLimitOrOverpay() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE core.account SET credit_limit_cents = 10 WHERE id = :id")
                .param("id", ana.accountId()).update()).hasStackTraceContaining("account_balance_check");
        assertThatThrownBy(() -> rawEntry(creditAccount(), "DEBIT", 3_000_01)).hasStackTraceContaining("account_balance_check");
        assertThatThrownBy(() -> rawEntry(creditAccount(), "CREDIT", 1)).hasStackTraceContaining("account_balance_check");
    }

    @Test
    void repeatedPurchaseIsChargedOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        List<JsonNode> results = contend(creditAccount(), 8, () -> body(buy(card, 90_00, "CREDIT", 2, false, key)));
        assertThat(results).allMatch(result -> result.has("id"));
        assertThat(new HashSet<>(results)).hasSize(1);
        assertThat(credit().get("usedCents").asLong()).isEqualTo(90_00);
        assertThat(code(buy(card, 90_00, "CREDIT", 3, false, key))).isEqualTo("idempotency.mismatch");
        assertThat(code(buy(card, 90_00, "DEBIT", 1, false, key))).isEqualTo("idempotency.mismatch");
    }

    @Test
    void cardSettingsAreRespected() throws Exception {
        change("{\"contactless\": false}");
        assertThat(code(buy(card, 10_00, "DEBIT", 1, true, key()))).isEqualTo("card.contactless_off");
        assertThat(buy(card, 10_00, "DEBIT", 1, false, key()).getStatus()).isEqualTo(200);
        change("{\"frozen\": true}");
        assertThat(code(buy(card, 10_00, "DEBIT", 1, false, key()))).isEqualTo("card.frozen");
        assertThat(code(buy(card, 10_00, "CREDIT", 1, false, key()))).isEqualTo("card.frozen");
    }

    @Test
    void payingFreesTheLimitAndCannotPassTheInvoice() throws Exception {
        buy(300_00, "CREDIT", 1);
        assertThat(code(pay("2026-12", 300_01))).isEqualTo("invoice.overpaid");
        assertThat(code(pay("2027-06", 1))).isEqualTo("invoice.overpaid");
        assertThat(pay("2026-12", 100_00).getStatus()).isEqualTo(200);
        assertThat(invoice("2026-12").get("remainingCents").asLong()).isEqualTo(200_00);
        assertThat(credit().get("usedCents").asLong()).isEqualTo(200_00);
        assertThat(checking()).isEqualTo(900_00);
    }

    @Test
    void simultaneousPaymentsNeverOverpay() throws Exception {
        buy(300_00, "CREDIT", 1);
        List<Integer> statuses = contend(ana.accountId(), 8, () -> pay("2026-12", 200_00).getStatus());
        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
        assertThat(checking()).isEqualTo(800_00);

        String key = key();
        List<Integer> same = contend(ana.accountId(), 6, () -> api.send("/credit/invoices/2026-12/payments",
                "{\"amountCents\": 10000}", key).getStatus());
        assertThat(same).allMatch(s -> s == 200);
        assertThat(checking()).isEqualTo(700_00);
    }

    @Test
    void invoiceGoesFromOpenToOverdueAndBlocksCredit() throws Exception {
        buy(50_00, "CREDIT", 1);
        assertThat(invoice("2026-12").get("status").asString()).isEqualTo("OPEN");
        dayIs(LocalDate.of(2026, 11, 25));
        assertThat(invoice("2026-12").get("status").asString()).isEqualTo("CLOSED");
        dayIs(LocalDate.of(2026, 12, 5));
        assertThat(invoice("2026-12").get("status").asString()).as("no vencimento ainda dá tempo").isEqualTo("CLOSED");
        assertThat(buy(10_00, "CREDIT", 1).getStatus()).isEqualTo(200);

        dayIs(LocalDate.of(2026, 12, 6));
        assertThat(invoice("2026-12").get("status").asString()).isEqualTo("OVERDUE");
        assertThat(credit().get("blocked").asBoolean()).isTrue();
        assertThat(code(buy(10_00, "CREDIT", 1))).isEqualTo("credit.blocked");
        assertThat(code(convert(100_00, 1, 104_50))).isEqualTo("credit.blocked");
        assertThat(buy(10_00, "DEBIT", 1).getStatus()).as("débito continua").isEqualTo(200);

        pay("2026-12", 50_00);
        assertThat(invoice("2026-12").get("status").asString()).isEqualTo("PAID");
        assertThat(buy(10_00, "CREDIT", 1).getStatus()).isEqualTo(200);
    }

    @Test
    void conversionShowsTheCostAndOnlyAcceptsWhatWasShown() throws Exception {
        JsonNode quote = body(api.read("/credit/conversion?amountCents=20000&installments=3"));
        assertThat(quote.get("feeCents").asLong()).as("4,5% + 2 x 2,5%").isEqualTo(19_00);
        assertThat(quote.get("totalCents").asLong()).isEqualTo(219_00);

        assertThat(code(convert(200_00, 3, 218_99))).isEqualTo("credit.quote_changed");
        assertThat(convert(200_00, 3, 219_00).getStatus()).isEqualTo(200);
        assertThat(checking()).isEqualTo(1_200_00);
        assertThat(credit().get("usedCents").asLong()).isEqualTo(219_00);
        assertThat(invoice("2027-02").get("totalCents").asLong()).isEqualTo(73_00);
    }

    @Test
    void conversionRespectsLimitAndRepeats() throws Exception {
        String key = key();
        String json = "{\"amountCents\": 10000, \"installments\": 1, \"acceptedTotalCents\": 10450}";
        List<Integer> statuses = contend(creditAccount(), 6, () -> api.send("/credit/conversions", json, key).getStatus());
        assertThat(statuses).allMatch(s -> s == 200);
        assertThat(checking()).isEqualTo(1_100_00);
        assertThat(code(convert(2_900_00, 1, 3_030_50))).isEqualTo("credit.limit");
        assertThat(code(convert(49_99, 1, 52_24))).isEqualTo("request.invalid");
    }

    @Test
    void impossiblePurchasesAreRefused() throws Exception {
        assertThat(code(buy(100_00, "DEBIT", 2))).isEqualTo("purchase.installments");
        assertThat(code(buy(20_00, "CREDIT", 5))).isEqualTo("purchase.small_installment");
        assertThat(code(buy(1_000_01, "DEBIT", 1))).isEqualTo("ledger.insufficient_funds");
        assertThat(buy(0, "DEBIT", 1).getStatus()).isEqualTo(400);
        assertThat(buy(100_00, "CREDIT", 13).getStatus()).isEqualTo(400);
        Customer other = newCustomer();
        assertThat(code(new ApiClient(mvc, other.bearer()).send("/cards/" + card + "/purchases",
                json(10_00, "DEBIT", 1, false)))).isEqualTo("card.not_found");
        assertThat(checking()).isEqualTo(1_000_00);
    }

    @Test
    void creditAccountDoesNotAppearAsMoney() throws Exception {
        assertThat(body(api.read("/accounts")).size()).isEqualTo(1);
    }

    private void rawEntry(UUID account, String direction, long cents) {
        transaction.executeWithoutResult(s -> {
            UUID tx = UUID.randomUUID();
            jdbc.sql("INSERT INTO core.ledger_transaction (id, kind, description) VALUES (:id, 'REVERSAL', 'teste')")
                    .param("id", tx).update();
            jdbc.sql("""
                            INSERT INTO core.ledger_entry (transaction_id, account_id, direction, amount_cents)
                            VALUES (:tx, :account, :direction, :cents)""")
                    .param("tx", tx).param("account", account).param("direction", direction).param("cents", cents)
                    .update();
        });
    }

    private void dayIs(LocalDate day) {
        clock.set(day.atTime(LocalTime.NOON).atZone(TestClock.SAO_PAULO));
    }

    private JsonNode credit() throws Exception {
        return body(api.read("/credit"));
    }

    private JsonNode invoice(String month) throws Exception {
        for (JsonNode invoice : credit().get("invoices")) {
            if (invoice.get("reference").asString().startsWith(month)) {
                return invoice;
            }
        }
        throw new AssertionError("sem fatura " + month);
    }

    private MockHttpServletResponse buy(long cents, String mode, int installments) throws Exception {
        return buy(card, cents, mode, installments, false, key());
    }

    private MockHttpServletResponse buy(String cardId, long cents, String mode, int installments, boolean contactless,
            String key) throws Exception {
        return api.send("/cards/" + cardId + "/purchases", json(cents, mode, installments, contactless), key);
    }

    private static String json(long cents, String mode, int installments, boolean contactless) {
        return "{\"merchant\": \"FAROL\", \"amountCents\": %d, \"mode\": \"%s\", \"installments\": %d, \"contactless\": %s}"
                .formatted(cents, mode, installments, contactless);
    }

    private MockHttpServletResponse pay(String month, long cents) throws Exception {
        return api.send("/credit/invoices/" + month + "/payments", "{\"amountCents\": " + cents + "}");
    }

    private MockHttpServletResponse convert(long cents, int installments, long accepted) throws Exception {
        return api.send("/credit/conversions",
                "{\"amountCents\": %d, \"installments\": %d, \"acceptedTotalCents\": %d}"
                        .formatted(cents, installments, accepted));
    }

    private void change(String json) throws Exception {
        mvc.perform(patch("/api/v1/cards/" + card).header("Authorization", ana.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return body(response).get("code").asString();
    }

    private UUID creditAccount() {
        return jdbc.sql("SELECT id FROM core.account WHERE owner_id = :id AND kind = 'CREDIT'")
                .param("id", ana.userId()).query(UUID.class).single();
    }

    private long checking() {
        return jdbc.sql("SELECT balance_cents FROM core.account WHERE id = :id")
                .param("id", ana.accountId()).query(Long.class).single();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
