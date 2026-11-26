package dev.lastro.core.invest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import dev.lastro.core.invest.MarketSource.News;
import dev.lastro.core.invest.MarketSource.Quote;
import dev.lastro.core.support.ApiClient;
import dev.lastro.core.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

class InvestFlowTest extends IntegrationTest {

    private static final java.util.concurrent.atomic.AtomicInteger TESTS = new java.util.concurrent.atomic.AtomicInteger();

    @MockitoBean private MarketSource source;

    private final AtomicReference<BigDecimal> btc = new AtomicReference<>();
    private Customer ana;
    private ApiClient api;

    @BeforeEach
    void setUp() throws Exception {
        ana = newCustomer();
        api = new ApiClient(mvc, ana.bearer());
        btc.set(new BigDecimal("400000"));
        // O cache de cotações vive entre testes: cada um começa uma hora depois do anterior.
        clock.set(clock.instant().plus(Duration.ofHours(TESTS.incrementAndGet())).atZone(java.time.ZoneOffset.UTC));
        when(source.quotes()).thenAnswer(call -> prices());
        when(source.history(any(), anyInt())).thenReturn(List.of());
        when(source.news()).thenReturn(List.of(new News("Dólar cai", "https://exemplo.com", "Fonte", Instant.EPOCH)));
    }

    private Map<Asset, Quote> prices() {
        Map<Asset, Quote> map = new EnumMap<>(Asset.class);
        for (Asset asset : Asset.values()) {
            BigDecimal bid = asset == Asset.BTC ? btc.get() : new BigDecimal("5.80");
            BigDecimal ask = asset == Asset.BTC ? btc.get().add(BigDecimal.TEN) : new BigDecimal("5.85");
            map.put(asset, new Quote(bid, ask, BigDecimal.ONE, Instant.now()));
        }
        return map;
    }

    @Test
    void buyingTurnsReaisIntoUnitsAtTheAskPrice() throws Exception {
        JsonNode trade = body(buy("USD", 100_00, "5.85"));
        assertThat(trade.get("quantity").asString()).isEqualTo("17.09"); // 100 / 5,85 = 17,094 -> 17,09
        assertThat(checking()).isEqualTo(900_00);
        JsonNode position = portfolio().get("positions").get(0);
        assertThat(position.get("costCents").asLong()).isEqualTo(100_00);
        assertThat(position.get("valueCents").asLong()).as("17,09 x 5,80 do lado da venda").isEqualTo(99_12);
        assertThat(portfolio().get("resultCents").asLong()).isEqualTo(-88);
    }

    @Test
    void satoshiPrecisionAndSellingEverythingClosesThePosition() throws Exception {
        JsonNode trade = body(buy("BTC", 100_00, "400010"));
        assertThat(trade.get("quantity").asString()).isEqualTo("0.00024999");
        btc.set(new BigDecimal("480000"));
        advance();
        JsonNode sold = body(sell("BTC", null, true, "480000"));
        assertThat(sold.get("amountCents").asLong()).isEqualTo(119_99);
        assertThat(checking()).isEqualTo(900_00 + 119_99);
        assertThat(portfolio().get("positions").size()).isZero();
        long cost = jdbc.sql("SELECT cost_cents FROM core.position WHERE owner_id = :id").param("id", ana.userId())
                .query(Long.class).single();
        assertThat(cost).isZero();
    }

    @Test
    void partialSaleKeepsTheAverageCost() throws Exception {
        buy("USD", 117_00, "5.85"); // 20 dólares
        JsonNode sold = body(sell("USD", 29_00L, false, "5.80")); // 29 / 5,80 = 5 dólares
        assertThat(sold.get("quantity").asString()).isEqualTo("5");
        JsonNode position = portfolio().get("positions").get(0);
        assertThat(position.get("quantity").asString()).isEqualTo("15");
        assertThat(position.get("costCents").asLong()).as("3/4 do custo").isEqualTo(87_75);
    }

    @Test
    void cannotSellMoreThanYouHaveEvenAllAtOnce() throws Exception {
        buy("USD", 117_00, "5.85");
        List<Integer> statuses = contend(ana.accountId(), 8, () -> sell("USD", 40_00L, false, "5.80").getStatus());
        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(2);
        assertThat(portfolio().get("positions").get(0).get("quantity").asString()).isEqualTo("6.2");
        assertThat(code(sell("USD", 40_00L, false, "5.80"))).isEqualTo("invest.not_enough");
        assertThat(code(sell("ETH", null, true, "5.80"))).isEqualTo("invest.not_enough");
    }

    @Test
    void repeatedOrderExecutesOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        List<JsonNode> trades = contend(ana.accountId(), 8, () -> body(api.send("/invest/buy",
                "{\"asset\":\"BTC\",\"amountCents\":5000,\"expectedPrice\":400010}", key)));
        assertThat(trades).allMatch(trade -> trade.has("id"));
        assertThat(new HashSet<>(trades)).hasSize(1);
        assertThat(checking()).isEqualTo(950_00);
        assertThat(code(api.send("/invest/buy", "{\"asset\":\"BTC\",\"amountCents\":5001,\"expectedPrice\":400010}", key)))
                .isEqualTo("idempotency.mismatch");
    }

    @Test
    void priceThatMovedMoreThanOnePercentNeedsConfirmation() throws Exception {
        assertThat(code(buy("BTC", 100_00, "395000"))).isEqualTo("invest.price_changed");
        assertThat(buy("BTC", 100_00, "397000").getStatus()).as("0,76% de diferença").isEqualTo(200);
        assertThat(checking()).isEqualTo(900_00);
    }

    @Test
    void sourceOfflineUsesRecentPricesAndThenStopsTrading() throws Exception {
        assertThat(api.read("/market").getStatus()).isEqualTo(200);
        when(source.quotes()).thenThrow(new IllegalStateException("fora do ar"));
        advance();
        JsonNode market = body(api.read("/market"));
        assertThat(market.get("stale").asBoolean()).isTrue();
        assertThat(buy("USD", 20_00, "5.85").getStatus()).as("preço de 2 minutos").isEqualTo(200);

        clock.set(clock.instant().plus(Duration.ofMinutes(5)).atZone(java.time.ZoneOffset.UTC));
        assertThat(code(api.read("/market"))).isEqualTo("market.unavailable");
        assertThat(code(buy("USD", 20_00, "5.85"))).isEqualTo("market.unavailable");
        Mockito.reset(source);
    }

    @Test
    void newsFailureIsAnEmptyList() throws Exception {
        assertThat(body(api.read("/market/news")).size()).isEqualTo(1);
        assertThat(api.read("/market/BTC/history?days=30").getStatus()).isEqualTo(200);
        assertThat(api.read("/market/BTC/history?days=31").getStatus()).isEqualTo(400);
        assertThat(api.read("/market/XYZ/history").getStatus()).isEqualTo(400);
    }

    @Test
    void limitsAndBalanceAreRespected() throws Exception {
        assertThat(buy("USD", 9_99, "5.85").getStatus()).isEqualTo(400);
        assertThat(code(buy("USD", 1_000_01, "5.85"))).isEqualTo("ledger.insufficient_funds");
        assertThat(sell("USD", 10_00L, true, "5.80").getStatus()).as("valor e tudo ao mesmo tempo").isEqualTo(400);
        assertThat(sell("USD", null, false, "5.80").getStatus()).isEqualTo(400);
        assertThat(checking()).isEqualTo(1_000_00);
    }

    private void advance() {
        clock.set(clock.instant().plus(Duration.ofMinutes(2)).atZone(java.time.ZoneOffset.UTC));
    }

    private MockHttpServletResponse buy(String asset, long cents, String price) throws Exception {
        return api.send("/invest/buy", "{\"asset\":\"%s\",\"amountCents\":%d,\"expectedPrice\":%s}"
                .formatted(asset, cents, price));
    }

    private MockHttpServletResponse sell(String asset, Long cents, boolean all, String price) throws Exception {
        return api.send("/invest/sell", "{\"asset\":\"%s\",\"amountCents\":%s,\"all\":%s,\"expectedPrice\":%s}"
                .formatted(asset, cents, all, price));
    }

    private JsonNode portfolio() throws Exception {
        return body(api.read("/invest"));
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return body(response).get("code").asString();
    }

    private long checking() {
        return jdbc.sql("SELECT balance_cents FROM core.account WHERE id = :id")
                .param("id", ana.accountId()).query(Long.class).single();
    }
}
