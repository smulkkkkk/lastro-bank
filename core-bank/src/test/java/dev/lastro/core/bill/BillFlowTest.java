package dev.lastro.core.bill;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lastro.core.support.ApiClient;
import dev.lastro.core.support.IntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

class BillFlowTest extends IntegrationTest {

    private Customer ana;
    private ApiClient api;

    @BeforeEach
    void setUp() throws Exception {
        ana = newCustomer();
        api = new ApiClient(mvc, ana.bearer());
    }

    private JsonNode sample() throws Exception {
        MockHttpServletResponse response = api.send("/bills/boletos/sample", "{}");
        assertThat(response.getStatus()).isEqualTo(201);
        return body(response);
    }

    private MockHttpServletResponse pay(String line, String key) throws Exception {
        return api.send("/bills/boletos/pay", json.writeValueAsString(java.util.Map.of("line", line)), key);
    }

    private long balance(UUID account) {
        return jdbc.sql("SELECT balance_cents FROM core.account WHERE id = :id").param("id", account)
                .query(Long.class).single();
    }

    @Test
    void lookupShowsWhatWillBePaid() throws Exception {
        JsonNode boleto = sample();
        JsonNode found = body(api.send("/bills/boletos/lookup", "{\"line\":\"" + boleto.get("line").asString() + "\"}"));
        assertThat(found.get("beneficiary").asString()).isEqualTo(boleto.get("beneficiary").asString());
        assertThat(found.get("amountCents").asLong()).isEqualTo(boleto.get("amountCents").asLong());
        assertThat(found.get("paid").asBoolean()).isFalse();
    }

    @Test
    void payingMovesTheMoneyOnceAndShowsInTheStatement() throws Exception {
        JsonNode boleto = sample();
        long amount = boleto.get("amountCents").asLong();
        String key = UUID.randomUUID().toString();
        MockHttpServletResponse first = pay(boleto.get("line").asString(), key);
        assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(200);
        assertThat(balance(ana.accountId())).isEqualTo(1_000_00 - amount);

        // Mesma chave: devolve o mesmo comprovante, sem cobrar de novo.
        MockHttpServletResponse again = pay(boleto.get("line").asString(), key);
        assertThat(body(again).get("id").asString()).isEqualTo(body(first).get("id").asString());
        assertThat(balance(ana.accountId())).isEqualTo(1_000_00 - amount);

        // Outra chave: o boleto já foi pago.
        assertThat(pay(boleto.get("line").asString(), UUID.randomUUID().toString()).getStatus()).isEqualTo(409);

        JsonNode statement = body(api.read("/accounts/" + ana.accountId() + "/statement"));
        assertThat(statement.get("items").get(0).get("description").asString())
                .isEqualTo("Boleto " + boleto.get("beneficiary").asString());
    }

    @Test
    void paymentGivesAReceiptOnlyTheOwnerCanSee() throws Exception {
        JsonNode boleto = sample();
        JsonNode receipt = body(pay(boleto.get("line").asString(), UUID.randomUUID().toString()));
        assertThat(receipt.get("recipient").asString()).isEqualTo(boleto.get("beneficiary").asString());
        assertThat(receipt.get("line").asString()).isEqualTo(boleto.get("line").asString());
        assertThat(receipt.get("dueDate").asString()).isEqualTo(boleto.get("dueDate").asString());
        assertThat(receipt.get("payerAccount").asString()).startsWith("Agência 0001, conta ");
        assertThat(receipt.get("authentication").asString()).matches("([0-9A-F]{4}\\.){7}[0-9A-F]{4}");

        String id = receipt.get("id").asString();
        JsonNode again = body(api.read("/bills/" + id));
        assertThat(again.get("authentication").asString()).as("o mesmo código toda vez")
                .isEqualTo(receipt.get("authentication").asString());
        assertThat(new ApiClient(mvc, newCustomer().bearer()).read("/bills/" + id).getStatus()).isEqualTo(404);
    }

    @Test
    void topUpReceiptNamesTheOperatorAndHidesThePhone() throws Exception {
        JsonNode receipt = body(api.send("/bills/top-ups",
                "{\"operator\":\"CORRENTE\",\"phone\":\"31991234567\",\"amountCents\":5000}", UUID.randomUUID().toString()));
        assertThat(receipt.get("recipient").asString()).isEqualTo("Corrente Telecom");
        assertThat(receipt.get("phone").asString()).isEqualTo("(31) •••••-4567");
        assertThat(receipt.get("line").isNull()).isTrue();
    }

    @Test
    void anotherCustomerCannotPayAPaidBoleto() throws Exception {
        JsonNode boleto = sample();
        pay(boleto.get("line").asString(), UUID.randomUUID().toString());
        ApiClient bia = new ApiClient(mvc, newCustomer().bearer());
        MockHttpServletResponse response = bia.send("/bills/boletos/pay",
                "{\"line\":\"" + boleto.get("line").asString() + "\"}", UUID.randomUUID().toString());
        assertThat(response.getStatus()).isEqualTo(409);
    }

    @Test
    void twoCustomersPayingTheSameBoletoAtOnceOnlyOneIsCharged() throws Exception {
        JsonNode boleto = sample();
        String line = boleto.get("line").asString();
        Customer bia = newCustomer();
        List<Integer> statuses = contend(ana.accountId(), 2, () -> {
            Customer who = statusesTurn() ? ana : bia;
            return new ApiClient(mvc, who.bearer()).send("/bills/boletos/pay", "{\"line\":\"" + line + "\"}",
                    UUID.randomUUID().toString()).getStatus();
        });
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        long charged = (1_000_00 - balance(ana.accountId())) + (1_000_00 - balance(bia.accountId()));
        assertThat(charged).isEqualTo(boleto.get("amountCents").asLong());
    }

    private final java.util.concurrent.atomic.AtomicInteger turn = new java.util.concurrent.atomic.AtomicInteger();

    private boolean statusesTurn() {
        return turn.getAndIncrement() % 2 == 0;
    }

    @Test
    void unregisteredOrOverdueBoletosAreRefused() throws Exception {
        String unknown = Boleto.create("341", 50_00, today().plusDays(3), "1".repeat(25)).line();
        assertThat(pay(unknown, UUID.randomUUID().toString()).getStatus()).isEqualTo(404);

        Boleto old = Boleto.create("777", 50_00, today().minusDays(2), "2".repeat(25));
        jdbc.sql("""
                        INSERT INTO core.boleto (barcode, beneficiary, amount_cents, due_date, created_at)
                        VALUES (:b, 'Luz do Vale Energia', 5000, :due, now())""")
                .param("b", old.barcode()).param("due", old.dueDate()).update();
        MockHttpServletResponse overdue = pay(old.line(), UUID.randomUUID().toString());
        assertThat(overdue.getStatus()).isEqualTo(422);
        assertThat(body(overdue).get("code").asString()).isEqualTo("boleto.overdue");
        assertThat(balance(ana.accountId())).isEqualTo(1_000_00);
    }

    @Test
    void notEnoughMoneyIsRefused() throws Exception {
        Boleto big = Boleto.create("777", 2_000_00, today().plusDays(3), "3".repeat(25));
        jdbc.sql("""
                        INSERT INTO core.boleto (barcode, beneficiary, amount_cents, due_date, created_at)
                        VALUES (:b, 'Escola Maré Alta', 200000, :due, now())""")
                .param("b", big.barcode()).param("due", big.dueDate()).update();
        MockHttpServletResponse response = pay(big.line(), UUID.randomUUID().toString());
        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(body(response).get("code").asString()).isEqualTo("ledger.insufficient_funds");
        assertThat(balance(ana.accountId())).isEqualTo(1_000_00);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), BillService.SAO_PAULO);
    }

    @Test
    void topUpChargesTheChosenAmountAndMasksThePhone() throws Exception {
        MockHttpServletResponse response = api.send("/bills/top-ups",
                "{\"operator\":\"ONDA\",\"phone\":\"11987654321\",\"amountCents\":3000}", UUID.randomUUID().toString());
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(body(response).get("description").asString()).isEqualTo("Recarga Onda Móvel (11) •••••-4321");
        assertThat(balance(ana.accountId())).isEqualTo(1_000_00 - 30_00);
        assertThat(body(api.read("/bills")).get(0).get("kind").asString()).isEqualTo("TOP_UP");
    }

    @Test
    void topUpRefusesOddAmountsAndLandlines() throws Exception {
        assertThat(api.send("/bills/top-ups", "{\"operator\":\"ONDA\",\"phone\":\"11987654321\",\"amountCents\":1234}",
                UUID.randomUUID().toString()).getStatus()).isEqualTo(422);
        assertThat(api.send("/bills/top-ups", "{\"operator\":\"ONDA\",\"phone\":\"1132654321\",\"amountCents\":3000}",
                UUID.randomUUID().toString()).getStatus()).isEqualTo(400);
        assertThat(balance(ana.accountId())).isEqualTo(1_000_00);
    }

    @Test
    void sameKeyForADifferentPaymentIsAConflict() throws Exception {
        String key = UUID.randomUUID().toString();
        api.send("/bills/top-ups", "{\"operator\":\"ONDA\",\"phone\":\"11987654321\",\"amountCents\":3000}", key);
        assertThat(api.send("/bills/top-ups", "{\"operator\":\"FAROL\",\"phone\":\"11987654321\",\"amountCents\":3000}", key)
                .getStatus()).isEqualTo(409);
    }
}
