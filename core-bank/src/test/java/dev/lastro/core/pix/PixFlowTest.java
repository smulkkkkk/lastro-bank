package dev.lastro.core.pix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.lastro.core.pix.SentinelClient.Analysis;
import dev.lastro.core.pix.SentinelClient.AnalysisRequest;
import dev.lastro.core.pix.SentinelClient.Decision;
import dev.lastro.core.support.IntegrationTest;
import dev.lastro.core.support.TestClock;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

/** Pix com o sentinel aprovando tudo; o que o sentinel decide está em SentinelFlowTest. */
class PixFlowTest extends IntegrationTest {

    @MockitoBean private SentinelClient sentinel;
    @Autowired private AnalysisWorker worker;

    private Customer ana;
    private Customer bia;
    private String biaKey;

    @BeforeEach
    void setUp() throws Exception {
        when(sentinel.analyze(any())).thenAnswer(call ->
                new Analysis(call.<AnalysisRequest>getArgument(0).transferId(), 0, Decision.APPROVED, List.of()));
        ana = newCustomer();
        bia = newCustomer();
        biaKey = newKey(bia, "EMAIL");
    }

    @Test
    void moneyMovesAfterAnalysisAndEachSideReadsItsOwnDescription() throws Exception {
        var response = send(ana, biaKey, 250_00, "almoço", id());
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode receipt = body(response);
        assertThat(receipt.get("status").asString()).isEqualTo("PENDING");
        assertThat(receipt.get("recipientName").asString()).isEqualTo("Cliente Teste");
        assertThat(balance(ana)).as("reservado, ainda não saiu").isEqualTo(1_000_00);
        assertThat(held(ana)).isEqualTo(250_00);

        settle();
        assertThat(status(ana, receipt)).isEqualTo("SETTLED");
        assertThat(balance(ana)).isEqualTo(750_00);
        assertThat(held(ana)).isZero();
        assertThat(balance(bia)).isEqualTo(1_250_00);
        assertThat(lastEntry(ana).get("description").asString()).isEqualTo("Pix para Cliente Teste");
        assertThat(lastEntry(bia).get("description").asString()).isEqualTo("Pix de Cliente Teste");
    }

    @Test
    void simultaneousEqualTransfersMoveMoneyOnce() throws Exception {
        String id = id();
        List<JsonNode> receipts = contend(ana.accountId(), 8, () -> body(send(ana, biaKey, 100_00, null, id)));
        assertThat(receipts).allMatch(receipt -> receipt.has("id"));
        assertThat(new HashSet<>(receipts)).hasSize(1);
        settle();
        assertThat(balance(ana)).isEqualTo(900_00);
    }

    @Test
    void sameIdempotencyKeyWithOtherContentIsRefused() throws Exception {
        String id = id();
        send(ana, biaKey, 100_00, "um", id);
        assertThat(code(send(ana, biaKey, 100_01, "um", id))).isEqualTo("idempotency.mismatch");
        assertThat(code(send(ana, biaKey, 100_00, "dois", id))).isEqualTo("idempotency.mismatch");
        settle();
        assertThat(balance(ana)).isEqualTo(900_00);
    }

    @Test
    void receiptSurvivesWhenTheRecipientDeletesTheKey() throws Exception {
        String id = id();
        JsonNode first = body(send(ana, biaKey, 10_00, null, id));
        deleteKeys(bia);
        assertThat(body(send(ana, biaKey, 10_00, null, id))).isEqualTo(first);
        assertThat(code(send(ana, biaKey, 10_00, null, id()))).isEqualTo("pix.key_not_found");
        settle();
        assertThat(balance(ana)).isEqualTo(990_00);
    }

    @Test
    void reservedMoneyIsNotSpentTwice() throws Exception {
        List<Integer> statuses = contend(ana.accountId(), 8, () -> send(ana, biaKey, 300_00, null, id()).getStatus());
        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(3);
        assertThat(statuses.stream().filter(s -> s == 422)).hasSize(5);
        assertThat(held(ana)).isEqualTo(900_00);
        settle();
        assertThat(balance(ana)).isEqualTo(100_00);
        assertThat(held(ana)).isZero();
    }

    @Test
    void crossedTransfersDoNotDeadlock() throws Exception {
        String anaKey = newKey(ana, "CPF");
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                boolean forward = i % 2 == 0;
                results.add(pool.submit(() -> (forward ? send(ana, biaKey, 10_00, null, id())
                        : send(bia, anaKey, 10_00, null, id())).getStatus()));
            }
            for (Future<Integer> result : results) {
                assertThat(result.get(60, TimeUnit.SECONDS)).isEqualTo(200);
            }
        }
        settle();
        settle();
        assertThat(balance(ana)).isEqualTo(1_000_00);
        assertThat(balance(bia)).isEqualTo(1_000_00);
    }

    @Test
    void dayLimitCountsWhatIsWaitingForAnalysis() throws Exception {
        deposit(ana, ana.accountId(), id(), "500000");
        List<Integer> statuses = contend(ana.accountId(), 8, () -> send(ana, biaKey, 900_00, null, id()).getStatus());
        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(5);
        assertThat(code(send(ana, biaKey, 500_01, null, id()))).isEqualTo("pix.limit");
        assertThat(send(ana, biaKey, 500_00, null, id()).getStatus()).isEqualTo(200);
    }

    @Test
    void nightLimitCrossesMidnightAndEndsInTheMorning() throws Exception {
        deposit(ana, ana.accountId(), id(), "500000");
        ZonedDateTime day = ZonedDateTime.now(TestClock.SAO_PAULO).minusDays(3);

        clock.set(day.with(LocalTime.of(19, 59)));
        assertThat(send(ana, biaKey, 500_00, null, id()).getStatus()).as("antes das 20h").isEqualTo(200);
        assertThat(limits().get("night").asBoolean()).isFalse();

        clock.set(day.with(LocalTime.of(20, 0)));
        assertThat(limits().get("night").asBoolean()).isTrue();
        assertThat(limits().get("availableCents").asLong()).isEqualTo(2_000_00);
        assertThat(send(ana, biaKey, 1_500_00, null, id()).getStatus()).isEqualTo(200);

        clock.set(day.plusDays(1).with(LocalTime.of(3, 0)));
        assertThat(limits().get("availableCents").asLong()).as("mesma noite").isEqualTo(500_00);
        assertThat(code(send(ana, biaKey, 500_01, null, id()))).isEqualTo("pix.limit");

        clock.set(day.plusDays(1).with(LocalTime.of(6, 0)));
        assertThat(limits().get("night").asBoolean()).isFalse();
        assertThat(limits().get("availableCents").asLong()).as("dia novo").isEqualTo(5_000_00);
    }

    @Test
    void lookupHidesMostOfTheCpfAndFindsFormattedKeys() throws Exception {
        String cpfKey = newKey(bia, "CPF");
        String formatted = cpfKey.replaceFirst("(...)(...)(...)(..)", "$1.$2.$3-$4");
        JsonNode found = body(lookup(ana, formatted));
        assertThat(found.get("name").asString()).isEqualTo("Cliente Teste");
        assertThat(found.get("maskedCpf").asString())
                .isEqualTo("***." + cpfKey.substring(3, 6) + "." + cpfKey.substring(6, 9) + "-**");
        assertThat(found.toString()).doesNotContain(bia.email()).doesNotContain(bia.accountId().toString());
        assertThat(lookup(ana, "  " + biaKey.toUpperCase() + " ").getStatus()).isEqualTo(200);
    }

    @Test
    void lookupsAreLimitedPerCustomer() throws Exception {
        int refused = 0;
        for (int i = 0; i < 25; i++) {
            if (lookup(ana, "ninguem" + i + "@teste.dev").getStatus() == 429) {
                refused++;
            }
        }
        assertThat(refused).isEqualTo(5);
        assertThat(lookup(bia, "ninguem@teste.dev").getStatus()).isEqualTo(404);
    }

    @Test
    void cannotPayYourselfOrUnknownKeys() throws Exception {
        String own = newKey(ana, "RANDOM");
        assertThat(code(send(ana, own, 1_00, null, id()))).isEqualTo("pix.self");
        assertThat(code(lookup(ana, own))).isEqualTo("pix.self");
        assertThat(code(send(ana, "nada@teste.dev", 1_00, null, id()))).isEqualTo("pix.key_not_found");
        assertThat(code(send(ana, biaKey, 1_000_01, null, id()))).isEqualTo("ledger.insufficient_funds");
        assertThat(held(ana)).isZero();
    }

    @Test
    void invalidTransfersAreRefused() throws Exception {
        assertThat(send(ana, biaKey, 0, null, id()).getStatus()).isEqualTo(400);
        assertThat(send(ana, biaKey, -5, null, id()).getStatus()).isEqualTo(400);
        assertThat(send(ana, " ", 100, null, id()).getStatus()).isEqualTo(400);
        assertThat(send(ana, biaKey, 100, "x".repeat(81), id()).getStatus()).isEqualTo(400);
        assertThat(send(ana, biaKey, 100, null, "curta").getStatus()).isEqualTo(400);
        assertThat(held(ana)).isZero();
    }

    @Test
    void keysBelongToTheirOwner() throws Exception {
        assertThat(createKey(bia, "EMAIL").getStatus()).as("repetida").isEqualTo(409);
        for (int i = 0; i < 3; i++) {
            assertThat(createKey(bia, "RANDOM").getStatus()).isEqualTo(201);
        }
        assertThat(code(createKey(bia, "RANDOM"))).isEqualTo("pix.too_many_keys");
        assertThat(createKey(bia, "TELEFONE").getStatus()).isEqualTo(400);

        String biaKeyId = keys(bia).get(0).get("id").asString();
        var stolen = mvc.perform(delete("/api/v1/pix/keys/" + biaKeyId).header("Authorization", ana.bearer()))
                .andReturn().getResponse();
        assertThat(stolen.getStatus()).isEqualTo(404);
        assertThat(keys(bia).size()).isEqualTo(4);
    }

    @Test
    void contactsShowEachPersonOnceOnlyAfterTheMoneyArrived() throws Exception {
        send(ana, biaKey, 1_00, null, id());
        assertThat(get("/api/v1/pix/contacts", ana).size()).as("ainda em análise").isZero();
        send(ana, newKey(bia, "RANDOM"), 1_00, null, id());
        Customer caio = newCustomer();
        send(ana, newKey(caio, "EMAIL"), 1_00, null, id());
        settle();
        assertThat(get("/api/v1/pix/contacts", ana).size()).isEqualTo(2);
        deleteKeys(caio);
        assertThat(get("/api/v1/pix/contacts", ana).size()).isEqualTo(1);
    }

    @Test
    void onlyTheSenderSeesTheStatus() throws Exception {
        JsonNode receipt = body(send(ana, biaKey, 1_00, null, id()));
        var peek = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/pix/transfers/" + receipt.get("id").asString()).header("Authorization", bia.bearer()))
                .andReturn().getResponse();
        assertThat(peek.getStatus()).isEqualTo(404);
    }

    private void settle() {
        worker.runOnce();
    }

    private String status(Customer customer, JsonNode receipt) throws Exception {
        return get("/api/v1/pix/transfers/" + receipt.get("id").asString(), customer).get("status").asString();
    }

    private JsonNode limits() throws Exception {
        return get("/api/v1/pix/limits", ana);
    }

    private JsonNode keys(Customer customer) throws Exception {
        return get("/api/v1/pix/keys", customer);
    }

    private JsonNode get(String path, Customer customer) throws Exception {
        return body(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header("Authorization", customer.bearer())).andReturn().getResponse());
    }

    private JsonNode lastEntry(Customer customer) throws Exception {
        return get("/api/v1/accounts/" + customer.accountId() + "/statement", customer).get("items").get(0);
    }

    private void deleteKeys(Customer customer) throws Exception {
        for (JsonNode key : keys(customer)) {
            mvc.perform(delete("/api/v1/pix/keys/" + key.get("id").asString())
                    .header("Authorization", customer.bearer()));
        }
    }

    private MockHttpServletResponse lookup(Customer customer, String key) throws Exception {
        return mvc.perform(post("/api/v1/pix/lookup").header("Authorization", customer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"key\": \"" + key + "\"}"))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse createKey(Customer customer, String kind) throws Exception {
        return mvc.perform(post("/api/v1/pix/keys").header("Authorization", customer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"kind\": \"" + kind + "\"}"))
                .andReturn().getResponse();
    }

    private String newKey(Customer customer, String kind) throws Exception {
        return body(createKey(customer, kind)).get("value").asString();
    }

    private MockHttpServletResponse send(Customer from, String key, long cents, String message, String idempotency)
            throws Exception {
        String text = message == null ? "null" : "\"" + message + "\"";
        return mvc.perform(post("/api/v1/pix/transfers").header("Authorization", from.bearer())
                .header("Idempotency-Key", idempotency)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\": \"%s\", \"amountCents\": %d, \"message\": %s}".formatted(key, cents, text)))
                .andReturn().getResponse();
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return body(response).get("code").asString();
    }

    private long balance(Customer customer) {
        return jdbc.sql("SELECT balance_cents FROM core.account WHERE id = :id")
                .param("id", customer.accountId()).query(Long.class).single();
    }

    private long held(Customer customer) {
        return jdbc.sql("SELECT held_cents FROM core.account WHERE id = :id")
                .param("id", customer.accountId()).query(Long.class).single();
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
