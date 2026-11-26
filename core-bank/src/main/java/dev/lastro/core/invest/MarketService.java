package dev.lastro.core.invest;

import dev.lastro.core.invest.MarketSource.News;
import dev.lastro.core.invest.MarketSource.Point;
import dev.lastro.core.invest.MarketSource.Quote;
import dev.lastro.core.shared.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Guarda as respostas por um tempo: a fonte gratuita limita chamadas, e mil clientes olhando o
 * bitcoin não precisam de mil consultas.
 */
@Service
class MarketService {

    static final Duration QUOTE_TTL = Duration.ofSeconds(60);
    /** Passado disso, negociar com o preço guardado seria injusto com alguém. */
    static final Duration QUOTE_MAX_AGE = Duration.ofMinutes(5);
    private static final Duration HISTORY_TTL = Duration.ofMinutes(10);
    private static final Duration NEWS_TTL = Duration.ofMinutes(15);
    private static final Logger log = LoggerFactory.getLogger(MarketService.class);

    record Prices(Map<Asset, Quote> quotes, Instant fetchedAt, boolean stale) {
    }

    private record Cached<T>(T value, Instant at) {
    }

    private final MarketSource source;
    private final Clock clock;
    private volatile Cached<Map<Asset, Quote>> quotes;
    private volatile Cached<List<News>> news;
    private final Map<String, Cached<List<Point>>> histories = new ConcurrentHashMap<>();

    MarketService(MarketSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    synchronized Prices prices() {
        Instant now = clock.instant();
        Cached<Map<Asset, Quote>> current = quotes;
        if (current == null || current.at().plus(QUOTE_TTL).isBefore(now)) {
            try {
                current = new Cached<>(source.quotes(), now);
                quotes = current;
            } catch (RuntimeException e) {
                log.warn("Cotações indisponíveis: {}", e.getMessage());
            }
        }
        if (current == null || current.at().plus(QUOTE_MAX_AGE).isBefore(now)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "market.unavailable",
                    "As cotações estão indisponíveis agora. Tente de novo em alguns minutos.");
        }
        return new Prices(current.value(), current.at(), current.at().plus(QUOTE_TTL).isBefore(now));
    }

    List<Point> history(Asset asset, int days) {
        String key = asset + ":" + days;
        Instant now = clock.instant();
        Cached<List<Point>> cached = histories.get(key);
        if (cached != null && cached.at().plus(HISTORY_TTL).isAfter(now)) {
            return cached.value();
        }
        try {
            List<Point> points = source.history(asset, days);
            histories.put(key, new Cached<>(points, now));
            return points;
        } catch (RuntimeException e) {
            if (cached != null) {
                return cached.value();
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "market.unavailable",
                    "O histórico está indisponível agora.");
        }
    }

    /** Sem notícias a tela continua funcionando; por isso a falha vira lista vazia. */
    synchronized List<News> news() {
        Instant now = clock.instant();
        if (news == null || news.at().plus(NEWS_TTL).isBefore(now)) {
            try {
                news = new Cached<>(source.news(), now);
            } catch (RuntimeException e) {
                log.warn("Notícias indisponíveis: {}", e.getMessage());
                return news == null ? List.of() : news.value();
            }
        }
        return news.value();
    }
}
