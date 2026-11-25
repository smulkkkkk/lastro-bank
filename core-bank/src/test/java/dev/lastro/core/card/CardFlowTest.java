package dev.lastro.core.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import dev.lastro.core.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

class CardFlowTest extends IntegrationTest {

    private Customer customer;

    @BeforeEach
    void setUp() throws Exception {
        customer = newCustomer();
    }

    @Test
    void newAccountHasThreeCardsAndOnePrimary() throws Exception {
        JsonNode cards = cards(customer);
        assertThat(cards.size()).isEqualTo(3);
        assertThat(cards.get(0).get("design").asString()).isEqualTo("ONIX");
        assertThat(cards.get(0).get("last4").asString()).matches("[0-9]{4}");
        assertThat(primaries()).containsExactly(0);
    }

    @Test
    void freezingChangesOnlyWhatWasAsked() throws Exception {
        String id = cards(customer).get(1).get("id").asString();
        JsonNode frozen = body(update(customer, id, "{\"frozen\": true}"));
        assertThat(frozen.get("frozen").asBoolean()).isTrue();
        assertThat(frozen.get("contactless").asBoolean()).isTrue();
        assertThat(frozen.get("primary").asBoolean()).isFalse();

        JsonNode noContact = body(update(customer, id, "{\"contactless\": false}"));
        assertThat(noContact.get("frozen").asBoolean()).isTrue();
        assertThat(noContact.get("contactless").asBoolean()).isFalse();
    }

    @Test
    void choosingAPrimaryReplacesThePreviousOne() throws Exception {
        String id = cards(customer).get(2).get("id").asString();
        assertThat(update(customer, id, "{\"primary\": true}").getStatus()).isEqualTo(200);
        assertThat(update(customer, id, "{\"primary\": true}").getStatus()).as("repetir").isEqualTo(200);
        assertThat(primaries()).containsExactly(2);
    }

    @Test
    void simultaneousChoicesLeaveExactlyOnePrimary() throws Exception {
        JsonNode cards = cards(customer);
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String id = cards.get(i % 3).get("id").asString();
                results.add(pool.submit(() -> update(customer, id, "{\"primary\": true}").getStatus()));
            }
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        }
        assertThat(primaries()).hasSize(1);
    }

    @Test
    void otherCustomersCardsAreInvisible() throws Exception {
        Customer other = newCustomer();
        String id = cards(customer).get(0).get("id").asString();
        assertThat(update(other, id, "{\"frozen\": true}").getStatus()).isEqualTo(404);
        assertThat(cards(customer).get(0).get("frozen").asBoolean()).isFalse();
        assertThat(cards(other).valueStream().map(card -> card.get("id").asString())).doesNotContain(id);
    }

    @Test
    void emptyOrImpossibleChangesAreRejected() throws Exception {
        String id = cards(customer).get(0).get("id").asString();
        assertThat(update(customer, id, "{}").getStatus()).isEqualTo(400);
        assertThat(update(customer, id, "{\"primary\": false}").getStatus()).isEqualTo(400);
        assertThat(update(customer, id, "{\"last4\": \"0000\"}").getStatus()).isEqualTo(400);
        assertThat(primaries()).containsExactly(0);
    }

    private List<Integer> primaries() throws Exception {
        JsonNode cards = cards(customer);
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).get("primary").asBoolean()) {
                found.add(i);
            }
        }
        return found;
    }

    private JsonNode cards(Customer owner) throws Exception {
        return body(mvc.perform(get("/api/v1/cards").header("Authorization", owner.bearer()))
                .andReturn().getResponse());
    }

    private MockHttpServletResponse update(Customer owner, String id, String content) throws Exception {
        return mvc.perform(patch("/api/v1/cards/{id}", id).header("Authorization", owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(content)).andReturn().getResponse();
    }
}
