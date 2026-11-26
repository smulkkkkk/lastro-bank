package dev.lastro.core.pix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.lastro.core.pix.SentinelClient.Analysis;
import dev.lastro.core.pix.SentinelClient.AnalysisRequest;
import dev.lastro.core.pix.SentinelClient.Decision;
import dev.lastro.core.support.ApiClient;
import dev.lastro.core.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

/** Cada decisão do Pix deixa na outbox os avisos certos, para as pessoas certas. */
class PixNotificationTest extends IntegrationTest {

    @MockitoBean private SentinelClient sentinel;
    @Autowired private AnalysisWorker worker;

    private final AtomicReference<Decision> answer = new AtomicReference<>(Decision.APPROVED);
    private Customer ana;
    private Customer bia;
    private String biaKey;

    @BeforeEach
    void setUp() throws Exception {
        when(sentinel.analyze(any())).thenAnswer(call -> new Analysis(call.<AnalysisRequest>getArgument(0).transferId(),
                10, answer.get(), List.of()));
        ana = newCustomer();
        bia = newCustomer();
        biaKey = body(new ApiClient(mvc, bia.bearer()).send("/pix/keys", "{\"kind\":\"EMAIL\"}")).get("value").asString();
    }

    @Test
    void settledPixTellsBothSides() throws Exception {
        send(123_45);
        assertThat(notifications(ana.userId())).isEmpty();
        worker.runOnce();

        List<JsonNode> toAna = notifications(ana.userId());
        List<JsonNode> toBia = notifications(bia.userId());
        assertThat(toAna).singleElement().satisfies(n -> {
            assertThat(n.get("kind").asString()).isEqualTo("PIX_SENT");
            assertThat(n.get("body").asString()).startsWith("R$ 123,45 para ");
        });
        assertThat(toBia).singleElement().satisfies(n -> {
            assertThat(n.get("kind").asString()).isEqualTo("PIX_RECEIVED");
            assertThat(n.get("title").asString()).isEqualTo("Pix recebido");
            assertThat(n.get("body").asString()).startsWith("R$ 123,45 de ");
            assertThat(n.get("link").asString()).isEqualTo("/app/extrato");
            assertThat(n.get("userId").asString()).isEqualTo(bia.userId().toString());
        });
    }

    @Test
    void deniedPixOnlyTellsTheSender() throws Exception {
        answer.set(Decision.DENIED);
        send(300_00);
        worker.runOnce();
        assertThat(notifications(ana.userId())).singleElement()
                .satisfies(n -> assertThat(n.get("kind").asString()).isEqualTo("PIX_DENIED"));
        assertThat(notifications(bia.userId())).as("quem receberia não fica sabendo").isEmpty();
    }

    @Test
    void reviewWarnsTheSenderAndTheFinalDecisionWarnsAgain() throws Exception {
        answer.set(Decision.REVIEW);
        String id = send(300_00);
        worker.runOnce();
        assertThat(notifications(ana.userId())).extracting(n -> n.get("kind").asString()).containsExactly("PIX_REVIEW");
        jdbc.sql("UPDATE core.pix_transfer SET created_at = created_at - interval '25 hours' WHERE id = :id")
                .param("id", UUID.fromString(id)).update();
        worker.runOnce();
        assertThat(notifications(ana.userId())).extracting(n -> n.get("kind").asString())
                .containsExactly("PIX_REVIEW", "PIX_CANCELLED");
    }

    @Test
    void aLateAnswerForACancelledPixSendsNothing() throws Exception {
        String id = send(300_00);
        jdbc.sql("UPDATE core.pix_transfer SET created_at = created_at - interval '11 minutes' WHERE id = :id")
                .param("id", UUID.fromString(id)).update();
        worker.runOnce();
        assertThat(notifications(bia.userId())).isEmpty();
        assertThat(notifications(ana.userId())).extracting(n -> n.get("kind").asString()).containsExactly("PIX_CANCELLED");
    }

    /**
     * Dois Pix no mesmo lote são decididos na ordem em que foram feitos. O UPDATE ... RETURNING
     * devolve na ordem física das linhas; mexer na primeira a joga para o fim da tabela.
     */
    @Test
    void transfersInTheSameBatchAreDecidedInTheOrderTheyWereMade() throws Exception {
        String first = send(100_00);
        String second = send(200_00);
        jdbc.sql("UPDATE core.outbox SET created_at = created_at - interval '1 second' WHERE aggregate_id = :id")
                .param("id", UUID.fromString(first)).update();
        worker.runOnce();
        List<String> settled = jdbc.sql("""
                        SELECT p.id::text FROM core.pix_transfer p
                          JOIN core.ledger_entry e ON e.transaction_id = p.transaction_id AND e.direction = 'DEBIT'
                         WHERE p.id IN (:ids) ORDER BY e.id""")
                .param("ids", List.of(UUID.fromString(first), UUID.fromString(second))).query(String.class).list();
        assertThat(settled).containsExactly(first, second);
    }

    private String send(long cents) throws Exception {
        return body(new ApiClient(mvc, ana.bearer()).send("/pix/transfers",
                "{\"key\":\"%s\",\"amountCents\":%d}".formatted(biaKey, cents))).get("id").asString();
    }

    private List<JsonNode> notifications(UUID userId) {
        return jdbc.sql("""
                        SELECT payload::text FROM core.outbox
                         WHERE kind = 'NOTIFICATION' AND payload->>'userId' = :user ORDER BY created_at, payload->>'kind' DESC""")
                .param("user", userId.toString()).query(String.class).list().stream()
                .map(json::readTree).toList();
    }
}
