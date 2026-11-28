package dev.lastro.core.reward;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lastro.core.support.ApiClient;
import dev.lastro.core.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

class RewardFlowTest extends IntegrationTest {

    private Customer ana;
    private ApiClient api;
    private String cardId;

    @BeforeEach
    void setUp() throws Exception {
        ana = newCustomer();
        api = new ApiClient(mvc, ana.bearer());
        cardId = body(api.read("/cards")).get(0).get("id").asString();
    }

    private JsonNode buy(String merchant, long cents, String key) throws Exception {
        MockHttpServletResponse response = api.send("/cards/" + cardId + "/purchases",
                "{\"merchant\":\"%s\",\"amountCents\":%d,\"mode\":\"DEBIT\",\"installments\":1,\"contactless\":false}"
                        .formatted(merchant, cents), key);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return body(response);
    }

    private JsonNode buy(String merchant, long cents) throws Exception {
        return buy(merchant, cents, UUID.randomUUID().toString());
    }

    private long bubbles() throws Exception {
        return body(api.read("/rewards")).get("bubbles").asLong();
    }

    private long balance() {
        return jdbc.sql("SELECT balance_cents FROM core.account WHERE id = :id").param("id", ana.accountId())
                .query(Long.class).single();
    }

    @Test
    void purchasesEarnTheStoreCashbackInBubbles() throws Exception {
        buy("FAROL", 100_00);
        buy("CORRENTEZA", 50_00);
        // Livraria: 8% de R$ 100 = 800 bolhas. Posto: 1% de R$ 50 = 50 bolhas.
        assertThat(bubbles()).isEqualTo(850);
        JsonNode entries = body(api.read("/rewards")).get("entries");
        assertThat(entries.get(1).get("description").asString()).isEqualTo("Cashback Livraria Farol");
    }

    @Test
    void couponDiscountsTheNextPurchaseOnce() throws Exception {
        assertThat(api.send("/rewards/stores/FAROL/coupon", "{}").getStatus()).isEqualTo(201);
        JsonNode first = buy("FAROL", 100_00);
        // 20% de R$ 100, dentro do teto de R$ 30.
        assertThat(first.get("discountCents").asLong()).isEqualTo(20_00);
        assertThat(first.get("amountCents").asLong()).isEqualTo(80_00);
        assertThat(balance()).isEqualTo(1_000_00 - 80_00);
        // Cashback sobre o que foi cobrado: 8% de R$ 80.
        assertThat(bubbles()).isEqualTo(640);

        JsonNode second = buy("FAROL", 100_00);
        assertThat(second.get("discountCents").asLong()).as("o cupom vale uma compra").isZero();
        assertThat(api.send("/rewards/stores/FAROL/coupon", "{}").getStatus()).as("usado, dá para pegar outro")
                .isEqualTo(201);
    }

    @Test
    void couponHasACapAndAMinimum() throws Exception {
        api.send("/rewards/stores/FAROL/coupon", "{}");
        assertThat(buy("FAROL", 40_00).get("discountCents").asLong()).as("abaixo do mínimo de R$ 50").isZero();
        assertThat(buy("FAROL", 500_00).get("discountCents").asLong()).as("teto de R$ 30").isEqualTo(30_00);
    }

    @Test
    void anActiveCouponIsNotDuplicated() throws Exception {
        api.send("/rewards/stores/ABISSAL/coupon", "{}");
        assertThat(api.send("/rewards/stores/ABISSAL/coupon", "{}").getStatus()).isEqualTo(409);
        assertThat(api.send("/rewards/stores/NADA/coupon", "{}").getStatus()).isEqualTo(404);
        JsonNode stores = body(api.read("/rewards/stores"));
        JsonNode cinema = null;
        for (JsonNode store : stores) {
            if (store.get("merchant").asString().equals("ABISSAL")) cinema = store;
        }
        assertThat(cinema.get("name").asString()).isEqualTo("Cine Abissal");
        assertThat(cinema.get("coupon").isNull()).isFalse();
    }

    @Test
    void repeatingAPurchaseWithACouponReturnsTheSamePurchase() throws Exception {
        api.send("/rewards/stores/FAROL/coupon", "{}");
        String key = UUID.randomUUID().toString();
        JsonNode first = buy("FAROL", 100_00, key);
        JsonNode again = buy("FAROL", 100_00, key);
        assertThat(again.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(balance()).isEqualTo(1_000_00 - 80_00);
        assertThat(bubbles()).isEqualTo(640);
    }

    @Test
    void bubblesBecomeBalance() throws Exception {
        buy("FAROL", 100_00);
        long before = balance();
        MockHttpServletResponse redeemed = api.send("/rewards/redeem", "{\"bubbles\":500}", UUID.randomUUID().toString());
        assertThat(redeemed.getStatus()).isEqualTo(200);
        assertThat(body(redeemed).get("bubbles").asLong()).isEqualTo(300);
        assertThat(balance()).isEqualTo(before + 5_00);

        assertThat(api.send("/rewards/redeem", "{\"bubbles\":1000}", UUID.randomUUID().toString()).getStatus()).isEqualTo(422);
        assertThat(api.send("/rewards/redeem", "{\"bubbles\":50}", UUID.randomUUID().toString()).getStatus()).isEqualTo(422);
        JsonNode statement = body(api.read("/accounts/" + ana.accountId() + "/statement"));
        assertThat(statement.get("items").get(0).get("description").asString()).isEqualTo("Bolhas trocadas por saldo");
    }

    @Test
    void sameKeyRedeemsOnce() throws Exception {
        buy("FAROL", 100_00);
        String key = UUID.randomUUID().toString();
        api.send("/rewards/redeem", "{\"bubbles\":300}", key);
        api.send("/rewards/redeem", "{\"bubbles\":300}", key);
        assertThat(bubbles()).isEqualTo(500);
        assertThat(api.send("/rewards/redeem", "{\"bubbles\":200}", key).getStatus()).isEqualTo(409);
    }

    @Test
    void twoRedeemsAtOnceCannotSpendTheSameBubbles() throws Exception {
        buy("FAROL", 100_00);
        List<Integer> statuses = contend(ana.accountId(), 2, () -> api.send("/rewards/redeem", "{\"bubbles\":500}",
                UUID.randomUUID().toString()).getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);
        assertThat(bubbles()).isEqualTo(300);
    }

    @Test
    void bubbleHistoryCannotBeRewritten() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("DELETE FROM core.bubble_entry").update())
                .hasMessageContaining("append-only");
    }
}
