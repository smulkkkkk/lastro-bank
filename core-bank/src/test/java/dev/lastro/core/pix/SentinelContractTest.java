package dev.lastro.core.pix;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lastro.core.pix.SentinelClient.Analysis;
import dev.lastro.core.pix.SentinelClient.AnalysisRequest;
import dev.lastro.core.pix.SentinelClient.Decision;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Mesmos arquivos que o teste do Go lê: se um lado muda o formato, o outro quebra. */
class SentinelContractTest {

    private static final Path CONTRACTS = Path.of("..", "contracts");
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    @Test
    void requestHasExactlyTheShapeOfTheContract() throws Exception {
        AnalysisRequest request = new AnalysisRequest(UUID.fromString("6f1b2a4e-1c0d-4f7e-9a3b-2d5c8e7f9a10"),
                UUID.fromString("0b8c3e1a-7d42-4c1f-8e6a-5f9d2b4c7e31"), UUID.fromString("9d4e7f2a-3b1c-4e8d-a6f5-1c2b3d4e5f60"),
                90000, Instant.parse("2026-11-25T12:00:00Z"), Instant.parse("2026-11-25T23:30:00Z"));
        assertThat(json.readTree(json.writeValueAsString(request)))
                .isEqualTo(json.readTree(Files.readString(CONTRACTS.resolve("analysis-request.json"))));
    }

    @Test
    void responseOfTheContractIsUnderstood() throws Exception {
        Analysis analysis = json.readValue(Files.readString(CONTRACTS.resolve("analysis-response.json")), Analysis.class);
        assertThat(analysis.decision()).isEqualTo(Decision.REVIEW);
        assertThat(analysis.score()).isEqualTo(50);
        assertThat(analysis.reasons()).hasSize(2);
    }

    /** Mesmo vetor que o Go usa: timestamp, ponto e corpo, com o segredo. */
    @Test
    void signatureMatchesTheGoImplementation() {
        HttpSentinelClient client = new HttpSentinelClient(json, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                "http://localhost:1", "segredo-de-teste-do-lastro");
        // Calculado fora do Java, com Go e com .NET, que deram o mesmo valor. O teste do Go confere o mesmo.
        assertThat(client.sign("1700000000", "{}".getBytes())).isEqualTo("777a51ebb0384f86100ebda4307ff3ed9f89ca41acde72ce416edfe93f19d373");
    }
}
