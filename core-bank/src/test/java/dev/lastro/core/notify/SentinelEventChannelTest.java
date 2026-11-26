package dev.lastro.core.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.lastro.core.shared.Hmac;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SentinelEventChannelTest {

    private static final String SECRET = "segredo-de-teste-do-lastro";
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-25T15:00:00Z"), ZoneOffset.UTC);
    private final AtomicReference<Map<String, String>> received = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(202);
    private HttpServer server;
    private SentinelEventChannel channel;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/events", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.set(Map.of("path", exchange.getRequestURI().getPath(), "body", body,
                    "timestamp", exchange.getRequestHeaders().getFirst("X-Lastro-Timestamp"),
                    "signature", exchange.getRequestHeaders().getFirst("X-Lastro-Signature")));
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
        channel = new SentinelEventChannel(clock, "http://127.0.0.1:" + server.getAddress().getPort() + "/", SECRET);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsTheEventSignedLikeTheAnalyses() {
        String payload = "{\"id\":\"5a1f0c3e-9b7d-4e2a-8c61-2d4b7f9e0a13\"}";
        channel.deliver(UUID.randomUUID(), payload);
        Map<String, String> request = received.get();
        assertThat(request.get("path")).isEqualTo("/v1/events");
        assertThat(request.get("body")).isEqualTo(payload);
        assertThat(request.get("timestamp")).isEqualTo("1795618800");
        assertThat(request.get("signature")).isEqualTo(
                Hmac.sign(SECRET.getBytes(StandardCharsets.UTF_8), "1795618800", payload.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void anythingButAcceptedIsAFailure() {
        status.set(401);
        assertThatThrownBy(() -> channel.deliver(UUID.randomUUID(), "{}")).hasMessageContaining("401");
    }

    /** O que o Notifier grava tem exatamente os campos do exemplo em contracts/, que o Go também testa. */
    @Test
    void notifierWritesTheContractShape() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        JsonNode contract = json.readTree(Files.readString(Path.of("../contracts/notification-event.json")));
        var event = new Notifier.Event(UUID.randomUUID(), UUID.randomUUID(), Notifier.Kind.PIX_RECEIVED, "Pix recebido",
                "R$ 150,00 de Ana Souza", "/app/extrato", clock.instant());
        JsonNode written = json.readTree(json.writeValueAsString(event));
        assertThat(names(written)).isEqualTo(names(contract));
        assertThat(written.get("createdAt").asString()).isEqualTo(contract.get("createdAt").asString());
    }

    private static Set<String> names(JsonNode node) {
        Set<String> names = new TreeSet<>();
        for (Iterator<String> it = node.propertyNames().iterator(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }
}
