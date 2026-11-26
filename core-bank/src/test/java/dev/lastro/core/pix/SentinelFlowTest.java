package dev.lastro.core.pix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.lastro.core.pix.SentinelClient.Analysis;
import dev.lastro.core.pix.SentinelClient.AnalysisRequest;
import dev.lastro.core.pix.SentinelClient.Decision;
import dev.lastro.core.pix.SentinelClient.Reason;
import dev.lastro.core.support.ApiClient;
import dev.lastro.core.support.IntegrationTest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

class SentinelFlowTest extends IntegrationTest {

    @MockitoBean private SentinelClient sentinel;
    @Autowired private AnalysisWorker worker;
    @Autowired private PixSettlement settlement;

    private final AtomicReference<Decision> answer = new AtomicReference<>(Decision.APPROVED);
    private Customer ana;
    private Customer bia;
    private ApiClient api;
    private String biaKey;

    @BeforeEach
    void setUp() throws Exception {
        when(sentinel.analyze(any())).thenAnswer(call -> new Analysis(call.<AnalysisRequest>getArgument(0).transferId(),
                answer.get() == Decision.REVIEW ? 50 : 80, answer.get(),
                List.of(new Reason("NEW_RECIPIENT", 25, "Primeira transferência"))));
        ana = newCustomer();
        bia = newCustomer();
        api = new ApiClient(mvc, ana.bearer());
        biaKey = body(new ApiClient(mvc, bia.bearer()).send("/pix/keys", "{\"kind\":\"EMAIL\"}")).get("value").asString();
    }

    @Test
    void theRequestCarriesWhatTheRulesNeed() throws Exception {
        String id = send(300_00);
        worker.runOnce();
        ArgumentCaptor<AnalysisRequest> sent = ArgumentCaptor.forClass(AnalysisRequest.class);
        verify(sentinel).analyze(sent.capture());
        AnalysisRequest request = sent.getValue();
        assertThat(request.transferId()).isEqualTo(UUID.fromString(id));
        assertThat(request.accountId()).isEqualTo(ana.accountId());
        assertThat(request.recipientAccountId()).isEqualTo(bia.accountId());
        assertThat(request.amountCents()).isEqualTo(300_00);
        java.time.OffsetDateTime created = jdbc.sql("SELECT created_at FROM core.account WHERE id = :id")
                .param("id", ana.accountId()).query(java.time.OffsetDateTime.class).single();
        assertThat(request.accountCreatedAt()).isEqualTo(created.toInstant());
    }

    @Test
    void deniedTransferGivesTheMoneyBack() throws Exception {
        answer.set(Decision.DENIED);
        String id = send(300_00);
        worker.runOnce();
        assertThat(status(id)).isEqualTo("DENIED");
        assertThat(balance(ana)).isEqualTo(1_000_00);
        assertThat(held(ana)).isZero();
        assertThat(balance(bia)).isEqualTo(1_000_00);
        assertThat(transfers(ana)).as("nada no razão").isZero();
        String score = jdbc.sql("SELECT score::text FROM core.pix_transfer WHERE id = :id")
                .param("id", UUID.fromString(id)).query(String.class).single();
        assertThat(score).isEqualTo("80");
    }

    @Test
    void reviewKeepsTheMoneyReservedUntilAnAnalystDecides() throws Exception {
        answer.set(Decision.REVIEW);
        String approve = send(300_00);
        String deny = send(200_00);
        worker.runOnce();
        assertThat(status(approve)).isEqualTo("UNDER_REVIEW");
        assertThat(held(ana)).isEqualTo(500_00);

        ApiClient analyst = analyst();
        JsonNode queue = body(analyst.read("/backoffice/reviews"));
        assertThat(queue.size()).isGreaterThanOrEqualTo(2);
        JsonNode item = find(queue, approve);
        assertThat(item.get("score").asInt()).isEqualTo(50);
        assertThat(item.get("reasons").get(0).get("code").asString()).isEqualTo("NEW_RECIPIENT");

        assertThat(decide(analyst, approve, "APPROVE", "Cliente confirmou por telefone").getStatus()).isEqualTo(204);
        assertThat(decide(analyst, deny, "DENY", "Padrão de golpe conhecido").getStatus()).isEqualTo(204);
        assertThat(status(approve)).isEqualTo("SETTLED");
        assertThat(status(deny)).isEqualTo("DENIED");
        assertThat(balance(ana)).isEqualTo(700_00);
        assertThat(held(ana)).isZero();
        assertThat(code(decide(analyst, approve, "DENY", "Mudei de ideia"))).isEqualTo("review.closed");
    }

    @Test
    void onlyAnalystsAndNeverOnTheirOwnPix() throws Exception {
        answer.set(Decision.REVIEW);
        String id = send(300_00);
        worker.runOnce();
        assertThat(api.read("/backoffice/reviews").getStatus()).isEqualTo(403);
        assertThat(decide(api, id, "APPROVE", "Eu mesmo aprovo").getStatus()).isEqualTo(403);

        jdbc.sql("UPDATE core.app_user SET role = 'ANALYST' WHERE id = :id").param("id", bia.userId()).update();
        ApiClient recipientAnalyst = new ApiClient(mvc, token(bia.email()));
        assertThat(code(decide(recipientAnalyst, id, "APPROVE", "Quero receber logo"))).isEqualTo("review.own");
        assertThat(decide(analyst(), id, "APPROVE", "ok").getStatus()).as("motivo curto").isEqualTo(400);
        assertThat(status(id)).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void sentinelDownMeansRetriesAndThenCancellation() throws Exception {
        // doThrow não chama a resposta configurada no setUp, que espera um pedido de verdade.
        doThrow(new IllegalStateException("fora do ar")).when(sentinel).analyze(any());
        String id = send(300_00);
        worker.runOnce();
        worker.runOnce();
        assertThat(status(id)).as("ainda esperando").isEqualTo("PENDING");
        int attempts = jdbc.sql("SELECT attempts FROM core.outbox WHERE aggregate_id = :id")
                .param("id", UUID.fromString(id)).query(Integer.class).single();
        assertThat(attempts).as("a segunda passada respeitou a espera").isEqualTo(1);

        later(Duration.ofSeconds(3));
        worker.runOnce();
        assertThat(jdbc.sql("SELECT attempts FROM core.outbox WHERE aggregate_id = :id")
                .param("id", UUID.fromString(id)).query(Integer.class).single()).isEqualTo(2);

        later(AnalysisWorker.PENDING_LIMIT);
        worker.runOnce();
        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(held(ana)).isZero();
        assertThat(balance(ana)).isEqualTo(1_000_00);
        assertThat(jdbc.sql("SELECT count(*) FROM core.outbox WHERE aggregate_id = :id")
                .param("id", UUID.fromString(id)).query(Long.class).single()).isZero();
    }

    @Test
    void lateApprovalCannotRaiseACancelledPix() throws Exception {
        String id = send(300_00);
        later(AnalysisWorker.PENDING_LIMIT.plusSeconds(1));
        worker.runOnce();
        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(settlement.decide(UUID.fromString(id), PixSettlement.Outcome.SETTLED, 0, "[]")).isFalse();
        assertThat(balance(ana)).isEqualTo(1_000_00);
        assertThat(held(ana)).isZero();
    }

    @Test
    void forgottenReviewExpiresAfterADay() throws Exception {
        answer.set(Decision.REVIEW);
        String id = send(300_00);
        worker.runOnce();
        later(Duration.ofHours(23));
        worker.runOnce();
        assertThat(status(id)).isEqualTo("UNDER_REVIEW");
        later(Duration.ofHours(2));
        worker.runOnce();
        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(held(ana)).isZero();
    }

    @Test
    void parallelWorkersAnalyzeAndSettleEachPixOnce() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(send(10_00));
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                runs.add(pool.submit(worker::runOnce));
            }
            int decided = 0;
            for (Future<Integer> run : runs) {
                decided += run.get();
            }
            assertThat(decided).isEqualTo(6);
        }
        verify(sentinel, atMost(6)).analyze(any());
        assertThat(transfers(ana)).isEqualTo(6);
        assertThat(balance(ana)).isEqualTo(940_00);
        for (String id : ids) {
            assertThat(status(id)).isEqualTo("SETTLED");
        }
    }

    private ApiClient analyst() throws Exception {
        Customer person = newCustomer();
        jdbc.sql("UPDATE core.app_user SET role = 'ANALYST' WHERE id = :id").param("id", person.userId()).update();
        return new ApiClient(mvc, token(person.email()));
    }

    private String token(String email) throws Exception {
        return "Bearer " + body(login(email, PASSWORD)).get("accessToken").asString();
    }

    private MockHttpServletResponse decide(ApiClient who, String id, String decision, String reason) throws Exception {
        return who.send("/backoffice/reviews/" + id, "{\"decision\":\"%s\",\"reason\":\"%s\"}".formatted(decision, reason));
    }

    private static JsonNode find(JsonNode queue, String id) {
        for (JsonNode item : queue) {
            if (item.get("id").asString().equals(id)) {
                return item;
            }
        }
        throw new AssertionError("Pix fora da fila: " + id);
    }

    private void later(Duration duration) {
        clock.set(clock.instant().plus(duration).atZone(ZoneOffset.UTC));
    }

    private String send(long cents) throws Exception {
        MockHttpServletResponse response = api.send("/pix/transfers",
                "{\"key\":\"%s\",\"amountCents\":%d}".formatted(biaKey, cents));
        assertThat(response.getStatus()).isEqualTo(200);
        return body(response).get("id").asString();
    }

    private String status(String id) throws Exception {
        return body(api.read("/pix/transfers/" + id)).get("status").asString();
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return body(response).get("code").asString();
    }

    private long transfers(Customer customer) {
        return jdbc.sql("""
                        SELECT count(*) FROM core.ledger_entry e JOIN core.ledger_transaction t ON t.id = e.transaction_id
                         WHERE e.account_id = :id AND t.kind = 'TRANSFER'""")
                .param("id", customer.accountId()).query(Long.class).single();
    }

    private long balance(Customer customer) {
        return jdbc.sql("SELECT balance_cents FROM core.account WHERE id = :id")
                .param("id", customer.accountId()).query(Long.class).single();
    }

    private long held(Customer customer) {
        return jdbc.sql("SELECT held_cents FROM core.account WHERE id = :id")
                .param("id", customer.accountId()).query(Long.class).single();
    }
}
