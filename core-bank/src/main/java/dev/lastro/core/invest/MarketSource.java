package dev.lastro.core.invest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Cotações e notícias de fontes públicas. */
interface MarketSource {

    record Quote(BigDecimal bid, BigDecimal ask, BigDecimal changePercent, Instant at) {
    }

    record Point(LocalDate day, BigDecimal price) {
    }

    record News(String title, String link, String source, Instant publishedAt) {
    }

    Map<Asset, Quote> quotes();

    List<Point> history(Asset asset, int days);

    List<News> news();
}
