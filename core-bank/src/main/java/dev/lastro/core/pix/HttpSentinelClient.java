package dev.lastro.core.pix;

import dev.lastro.core.shared.Hmac;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class HttpSentinelClient implements SentinelClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json;
    private final Clock clock;
    private final URI endpoint;
    private final byte[] secret;

    HttpSentinelClient(JsonMapper json, Clock clock, @Value("${lastro.sentinel.url}") String url,
            @Value("${lastro.sentinel.secret}") String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalStateException("Defina LASTRO_SENTINEL_SECRET com pelo menos 16 caracteres, o mesmo do sentinel");
        }
        this.json = json;
        this.clock = clock;
        this.endpoint = URI.create(url.replaceAll("/+$", "") + "/v1/analyses");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Analysis analyze(AnalysisRequest request) {
        byte[] body = json.writeValueAsBytes(request);
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Lastro-Timestamp", timestamp)
                .header("X-Lastro-Signature", sign(timestamp, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        String trace = MDC.get("traceId");
        if (trace != null) {
            builder.header("X-Trace-Id", trace);
        }
        try {
            var response = http.send(builder.build(), BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("sentinel respondeu " + response.statusCode());
            }
            Analysis analysis = json.readValue(response.body(), Analysis.class);
            if (!request.transferId().equals(analysis.transferId()) || analysis.decision() == null) {
                throw new IllegalStateException("resposta do sentinel não corresponde ao pedido");
            }
            return analysis;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    String sign(String timestamp, byte[] body) {
        return Hmac.sign(secret, timestamp, body);
    }
}
