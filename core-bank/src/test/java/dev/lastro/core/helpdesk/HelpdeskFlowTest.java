package dev.lastro.core.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lastro.core.support.ApiClient;
import dev.lastro.core.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/** Sem chave da API nos testes: o Lino responde pelo FAQ, e o fluxo inteiro é o mesmo. */
class HelpdeskFlowTest extends IntegrationTest {

    private Customer ana;
    private ApiClient api;

    @BeforeEach
    void setUp() throws Exception {
        ana = newCustomer();
        api = new ApiClient(mvc, ana.bearer());
    }

    private JsonNode say(String text) throws Exception {
        MockHttpServletResponse response = api.send("/help/messages", json.writeValueAsString(java.util.Map.of("text", text)));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return body(response);
    }

    @Test
    void newCustomerHasNoConversation() throws Exception {
        JsonNode conversation = body(api.read("/help/conversation"));
        assertThat(conversation.get("status").asString()).isEqualTo("NEW");
        assertThat(conversation.get("messages")).isEmpty();
    }

    @Test
    void linoAnswersAndKeepsTheHistory() throws Exception {
        say("Como funciona a caixinha?");
        JsonNode conversation = say("E qual o meu saldo?");
        JsonNode messages = conversation.get("messages");
        assertThat(messages).hasSize(4);
        assertThat(messages.get(1).get("role").asString()).isEqualTo("ASSISTANT");
        assertThat(messages.get(1).get("source").asString()).isEqualTo("FAQ");
        assertThat(messages.get(1).get("text").asString()).contains("CDI");
        assertThat(messages.get(3).get("text").asString()).contains("R$ 1.000,00 disponível");
        assertThat(body(api.read("/help/conversation")).get("messages")).hasSize(4);
    }

    @Test
    void customersOnlySeeTheirOwnConversation() throws Exception {
        say("Oi");
        JsonNode other = body(new ApiClient(mvc, newCustomer().bearer()).read("/help/conversation"));
        assertThat(other.get("messages")).isEmpty();
    }

    @Test
    void handoffNeedsAConversationFirst() throws Exception {
        assertThat(api.send("/help/handoff", "{\"phone\":\"11987654321\"}").getStatus()).isEqualTo(409);
    }

    @Test
    void handoffGivesAProtocolAndQueuesWhatsappAndANotice() throws Exception {
        say("Meu cartão foi clonado");
        MockHttpServletResponse response = api.send("/help/handoff", "{\"phone\":\"11987654321\"}");
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode conversation = body(response);
        String protocol = conversation.get("protocol").asString();
        assertThat(protocol).matches("LST-\\d{8}-[0-9A-Z]{6}");
        assertThat(conversation.get("status").asString()).isEqualTo("HANDED_OFF");
        assertThat(conversation.get("messages").get(2).get("text").asString()).contains(protocol);

        JsonNode handoff = json.readTree(jdbc.sql("SELECT payload::text FROM core.outbox WHERE kind = 'SUPPORT_HANDOFF' AND payload->>'protocol' = :p")
                .param("p", protocol).query(String.class).single());
        assertThat(handoff.get("phone").asString()).isEqualTo("+5511987654321");
        assertThat(handoff.get("transcript").get(0).get("text").asString()).isEqualTo("Meu cartão foi clonado");

        String notice = jdbc.sql("SELECT payload->>'body' FROM core.outbox WHERE kind = 'NOTIFICATION' AND payload->>'userId' = :u")
                .param("u", ana.userId().toString()).query(String.class).single();
        assertThat(notice).contains(protocol);
    }

    @Test
    void afterHandoffANewMessageStartsANewConversation() throws Exception {
        say("Quero falar com alguém");
        api.send("/help/handoff", "{\"phone\":\"11987654321\"}");
        assertThat(api.send("/help/handoff", "{\"phone\":\"11987654321\"}").getStatus()).as("não abre dois protocolos").isEqualTo(409);
        JsonNode fresh = say("Oi de novo");
        assertThat(fresh.get("status").asString()).isEqualTo("OPEN");
        assertThat(fresh.get("messages")).hasSize(2);
    }

    @Test
    void invalidInputIsRefused() throws Exception {
        say("Oi");
        assertThat(api.send("/help/handoff", "{\"phone\":\"0800\"}").getStatus()).isEqualTo(400);
        assertThat(api.send("/help/messages", "{\"text\":\"   \"}").getStatus()).isEqualTo(400);
        assertThat(api.send("/help/messages", json.writeValueAsString(java.util.Map.of("text", "a".repeat(1001)))).getStatus())
                .isEqualTo(400);
    }

    @Test
    void chatIsRateLimited() throws Exception {
        for (int i = 0; i < HelpdeskService.MESSAGES_PER_MINUTE; i++) {
            say("Pergunta " + i);
        }
        assertThat(api.send("/help/messages", "{\"text\":\"mais uma\"}").getStatus()).isEqualTo(429);
    }
}
