package dev.lastro.core.invest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MarketParsingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static String quotes(String btcAsk) {
        StringBuilder body = new StringBuilder("{");
        for (Asset asset : Asset.values()) {
            String ask = asset == Asset.BTC ? btcAsk : "10.02";
            body.append("\"%sBRL\":{\"bid\":\"10\",\"ask\":\"%s\",\"pctChange\":\"-1.5\",\"timestamp\":\"1795649703\"},"
                    .formatted(asset.name(), ask));
        }
        return body.deleteCharAt(body.length() - 1).append('}').toString();
    }

    @Test
    void readsAwesomeApiQuotes() {
        var parsed = PublicMarketSource.parseQuotes(JSON, quotes("10.01"));
        assertThat(parsed).hasSize(Asset.values().length);
        assertThat(parsed.get(Asset.BTC).ask()).isEqualByComparingTo("10.01");
        assertThat(parsed.get(Asset.USD).changePercent()).isEqualByComparingTo("-1.5");
    }

    @Test
    void refusesIncoherentOrMissingPrices() {
        assertThatThrownBy(() -> PublicMarketSource.parseQuotes(JSON, quotes("9.99"))).as("compra abaixo da venda")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PublicMarketSource.parseQuotes(JSON, quotes("11"))).as("diferença de 10%")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PublicMarketSource.parseQuotes(JSON, "{\"USDBRL\":{\"bid\":\"5\",\"ask\":\"5\"}}"))
                .as("faltam ativos").isInstanceOf(IllegalStateException.class);
    }

    @Test
    void historyKeepsTheLastPointOfEachDayInOrder() {
        String body = "[{\"bid\":\"300\",\"timestamp\":\"1795649703\"},{\"bid\":\"100\",\"timestamp\":\"1795400000\"},"
                + "{\"bid\":\"200\",\"timestamp\":\"1795649000\"}]";
        var points = PublicMarketSource.parseHistory(JSON, body);
        assertThat(points).hasSize(2);
        assertThat(points.get(0).day()).isBefore(points.get(1).day());
        assertThat(points.get(1).price()).isEqualByComparingTo("300");
        assertThat(points.get(1).day()).isEqualTo(LocalDate.of(2026, 11, 25));
    }

    @Test
    void rssKeepsOnlySafeLinksAndRefusesExternalEntities() {
        String rss = """
                <rss><channel>
                <item><title>Dólar cai</title><link>https://exemplo.com/a</link><pubDate>Wed, 25 Nov 2026 20:00:00 -0300</pubDate></item>
                <item><title>Golpe</title><link>javascript:alert(1)</link><pubDate>Wed, 25 Nov 2026 19:00:00 -0300</pubDate></item>
                </channel></rss>""";
        var news = PublicMarketSource.parseRss("Fonte", rss);
        assertThat(news).hasSize(1);
        assertThat(news.get(0).link()).isEqualTo("https://exemplo.com/a");

        String attack = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///C:/Windows/win.ini\">]>"
                + "<rss><channel><item><title>&x;</title></item></channel></rss>";
        assertThatThrownBy(() -> PublicMarketSource.parseRss("Fonte", attack)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void valueRoundsDown() {
        assertThat(InvestService.valueCents(new BigDecimal("0.00028301"), new BigDecimal("353321"))).isEqualTo(99_99);
        assertThat(InvestService.valueCents(new BigDecimal("10.25"), new BigDecimal("5.821"))).isEqualTo(59_66);
    }
}
