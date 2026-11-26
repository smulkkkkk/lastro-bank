package dev.lastro.core.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import dev.lastro.core.helpdesk.Assistant.Line;
import dev.lastro.core.helpdesk.Assistant.Reply;
import dev.lastro.core.helpdesk.Assistant.Role;
import dev.lastro.core.helpdesk.Assistant.Snapshot;
import dev.lastro.core.helpdesk.Assistant.Turn;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Um servidor falso no lugar da API: nenhum teste gasta crédito nem precisa de internet. */
class ClaudeAssistantTest {

    private static final Snapshot ANA = new Snapshot("Ana", 1_500_00, 200_00,
            List.of(new Line("Pix", "CREDIT", 50_00, Instant.parse("2026-11-25T15:00:00Z"))));
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<String> answer = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<JsonNode> sent = new AtomicReference<>();
    private final AtomicReference<String> beta = new AtomicReference<>();
    private HttpServer server;
    private ClaudeAssistant lino;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            sent.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            beta.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
            byte[] body = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var client = AnthropicOkHttpClient.builder()
                .apiKey("chave-de-teste")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .maxRetries(0)
                .timeout(Duration.ofSeconds(5))
                .build();
        lino = new ClaudeAssistant(client, ClaudeAssistant.MODEL, new FaqAssistant());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String message(String stopReason, String text) {
        return """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[{"type":"text","text":"%s"}],"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":10,"output_tokens":5}}""".formatted(text, stopReason);
    }

    private Reply ask(String... texts) {
        List<Turn> history = new java.util.ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            history.add(new Turn(i % 2 == 0 ? Role.USER : Role.ASSISTANT, texts[i]));
        }
        return lino.reply(ANA, history);
    }

    @Test
    void answersWithClaude() {
        answer.set(message("end_turn", "Oi, Ana! Sua caixinha rende todo dia."));
        Reply reply = ask("Oi", "Olá!", "Como rende a caixinha?");
        assertThat(reply).isEqualTo(new Reply("Oi, Ana! Sua caixinha rende todo dia.", "CLAUDE"));

        JsonNode request = sent.get();
        assertThat(request.get("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(request.get("output_config").get("effort").asString()).isEqualTo("low");
        assertThat(request.get("fallbacks").asString()).isEqualTo("default");
        assertThat(beta.get()).contains("server-side-fallback-2026-07-01");
        assertThat(request.has("thinking")).as("Opus 5.5 sempre pensa; o parâmetro não vai").isFalse();
        assertThat(request.get("messages")).hasSize(3);
        assertThat(request.get("messages").get(0).get("role").asString()).isEqualTo("user");
        String system = request.get("system").asString();
        assertThat(system).contains("Você é o Lino").contains("R$ 1.500,00").contains("+R$ 50,00");
    }

    @Test
    void refusalFallsBackToTheFaq() {
        answer.set(message("refusal", ""));
        assertThat(ask("Como funciona o limite do Pix?").source()).isEqualTo("FAQ");
    }

    @Test
    void apiDownFallsBackToTheFaq() {
        status.set(529);
        answer.set("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}");
        Reply reply = ask("Qual meu saldo?");
        assertThat(reply.source()).isEqualTo("FAQ");
        assertThat(reply.text()).contains("R$ 1.500,00");
    }

    @Test
    void badKeyFallsBackToTheFaq() {
        status.set(401);
        answer.set("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");
        assertThat(ask("Oi").source()).isEqualTo("FAQ");
    }

    @Test
    void historyAlwaysStartsWithTheCustomerAndAlternates() {
        List<Turn> merged = ClaudeAssistant.merged(List.of(new Turn(Role.ASSISTANT, "boas-vindas"),
                new Turn(Role.USER, "a"), new Turn(Role.USER, "b"), new Turn(Role.ASSISTANT, "c")));
        assertThat(merged).containsExactly(new Turn(Role.USER, "a\n\nb"), new Turn(Role.ASSISTANT, "c"));
    }
}
