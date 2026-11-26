package dev.lastro.core.notify;

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
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Manda o aviso ao sentinel, que guarda e entrega ao site do cliente na hora. */
@Component
class SentinelEventChannel implements OutboxChannel {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Clock clock;
    private final URI endpoint;
    private final byte[] secret;

    SentinelEventChannel(Clock clock, @Value("${lastro.sentinel.url}") String url,
            @Value("${lastro.sentinel.secret}") String secret) {
        this.clock = clock;
        this.endpoint = URI.create(url.replaceAll("/+$", "") + "/v1/events");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String kind() {
        return "NOTIFICATION";
    }

    @Override
    public void deliver(UUID id, String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Lastro-Timestamp", timestamp)
                .header("X-Lastro-Signature", Hmac.sign(secret, timestamp, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            int status = http.send(request, BodyHandlers.discarding()).statusCode();
            if (status != 202) {
                throw new IllegalStateException("sentinel respondeu " + status);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
