package dev.lastro.core.credit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lastro.core.support.ApiClient;
import dev.lastro.core.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class ContactlessTest extends IntegrationTest {

    private ApiClient api;
    private String cardId;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, newCustomer().bearer());
        cardId = body(api.read("/cards")).get(0).get("id").asString();
    }

    private MockHttpServletResponse tap(String body) throws Exception {
        return api.send("/cards/" + cardId + "/purchases",
                "{\"merchant\":\"MARE_CHEIA\",\"amountCents\":1500,\"mode\":\"DEBIT\",\"installments\":1," + body + "}",
                UUID.randomUUID().toString());
    }

    @Test
    void theDeviceIsRecorded() throws Exception {
        MockHttpServletResponse watch = tap("\"contactless\":true,\"device\":\"WATCH\"");
        assertThat(watch.getStatus()).as(watch.getContentAsString()).isEqualTo(200);
        assertThat(body(watch).get("device").asString()).isEqualTo("WATCH");
        assertThat(body(tap("\"contactless\":true")).get("device").asString()).as("sem aparelho, é o cartão").isEqualTo("CARD");
        assertThat(body(tap("\"contactless\":false")).get("device").isNull()).isTrue();
    }

    @Test
    void cardTapSwitchOnlyBlocksThePlasticCard() throws Exception {
        jdbc.sql("UPDATE core.card SET contactless = false WHERE id = :id").param("id", UUID.fromString(cardId)).update();
        assertThat(tap("\"contactless\":true,\"device\":\"CARD\"").getStatus()).isEqualTo(422);
        // Celular e relógio usam token próprio.
        assertThat(tap("\"contactless\":true,\"device\":\"PHONE\"").getStatus()).isEqualTo(200);
    }

    @Test
    void deviceWithoutContactlessIsRefused() throws Exception {
        assertThat(tap("\"contactless\":false,\"device\":\"PHONE\"").getStatus()).isEqualTo(400);
    }
}
