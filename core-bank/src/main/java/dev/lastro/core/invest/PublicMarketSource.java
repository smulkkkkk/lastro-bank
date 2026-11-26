package dev.lastro.core.invest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AwesomeAPI para cotações e RSS públicos para notícias. Nenhum dado de cliente sai daqui: as
 * chamadas são iguais para todo mundo.
 */
@Component
class PublicMarketSource implements MarketSource {

    private static final String QUOTES = "https://economia.awesomeapi.com.br/json/last/";
    private static final String HISTORY = "https://economia.awesomeapi.com.br/json/daily/";
    private static final List<String[]> FEEDS = List.of(
            new String[] {"InfoMoney", "https://www.infomoney.com.br/feed/"},
            new String[] {"Money Times", "https://www.moneytimes.com.br/feed/"});
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final JsonMapper json;

    PublicMarketSource(JsonMapper json) {
        this.json = json;
    }

    @Override
    public Map<Asset, Quote> quotes() {
        String pairs = String.join(",", java.util.Arrays.stream(Asset.values()).map(a -> a.name() + "-BRL").toList());
        return parseQuotes(json, get(QUOTES + pairs));
    }

    @Override
    public List<Point> history(Asset asset, int days) {
        return parseHistory(json, get(HISTORY + asset.name() + "-BRL/" + days));
    }

    @Override
    public List<News> news() {
        List<News> all = new ArrayList<>();
        for (String[] feed : FEEDS) {
            try {
                all.addAll(parseRss(feed[0], get(feed[1])));
            } catch (RuntimeException e) {
                // Uma fonte fora do ar não derruba as outras.
            }
        }
        all.sort(Comparator.comparing(News::publishedAt).reversed());
        return all.stream().limit(8).toList();
    }

    private String get(String url) {
        try {
            var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "LastroBank/0.1 (projeto de demonstracao)").build(), BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(url + " respondeu " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Recusa a resposta inteira se faltar um ativo ou um preço vier estranho: preço errado vira dinheiro errado. */
    static Map<Asset, Quote> parseQuotes(JsonMapper json, String body) {
        JsonNode root = json.readTree(body);
        Map<Asset, Quote> quotes = new EnumMap<>(Asset.class);
        for (Asset asset : Asset.values()) {
            JsonNode node = root.path(asset.name() + "BRL");
            BigDecimal bid = positive(node.path("bid"));
            BigDecimal ask = positive(node.path("ask"));
            if (ask.compareTo(bid) < 0 || ask.compareTo(bid.multiply(BigDecimal.valueOf(1.05))) > 0) {
                throw new IllegalStateException("Compra e venda incoerentes em " + asset);
            }
            BigDecimal change = node.path("pctChange").isString() ? new BigDecimal(node.get("pctChange").asString()) : BigDecimal.ZERO;
            Instant at = Instant.ofEpochSecond(Long.parseLong(node.path("timestamp").asString("0")));
            quotes.put(asset, new Quote(bid, ask, change, at));
        }
        return quotes;
    }

    static List<Point> parseHistory(JsonMapper json, String body) {
        JsonNode root = json.readTree(body);
        if (!root.isArray()) {
            throw new IllegalStateException("Histórico inesperado");
        }
        // Vários pontos por dia às vezes: fica o último de cada dia, em ordem do mais antigo.
        Map<LocalDate, Point> byDay = new java.util.TreeMap<>();
        List<JsonNode> items = new ArrayList<>();
        root.forEach(items::add);
        items.sort(Comparator.comparingLong(n -> Long.parseLong(n.path("timestamp").asString("0"))));
        for (JsonNode item : items) {
            LocalDate day = Instant.ofEpochSecond(Long.parseLong(item.path("timestamp").asString("0")))
                    .atZone(SAO_PAULO).toLocalDate();
            byDay.put(day, new Point(day, positive(item.path("bid"))));
        }
        return List.copyOf(byDay.values());
    }

    /** Sem DTD nem entidades externas: XML de fora não pode ler arquivos daqui. */
    static List<News> parseRss(String source, String body) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            NodeList items = document.getElementsByTagName("item");
            List<News> news = new ArrayList<>();
            for (int i = 0; i < items.getLength() && news.size() < 6; i++) {
                Element item = (Element) items.item(i);
                String title = text(item, "title");
                String link = text(item, "link");
                String date = text(item, "pubDate");
                if (title.isBlank() || !link.matches("https://[^\\s\"<>]+") || date.isBlank()) {
                    continue;
                }
                news.add(new News(title.length() > 160 ? title.substring(0, 157) + "…" : title, link, source,
                        ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()));
            }
            return news;
        } catch (Exception e) {
            throw new IllegalStateException("RSS inválido de " + source, e);
        }
    }

    private static String text(Element item, String tag) {
        NodeList nodes = item.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().strip();
    }

    private static BigDecimal positive(JsonNode node) {
        if (!node.isString()) {
            throw new IllegalStateException("Preço ausente");
        }
        BigDecimal value = new BigDecimal(node.asString());
        if (value.signum() <= 0) {
            throw new IllegalStateException("Preço não positivo");
        }
        return value;
    }
}
