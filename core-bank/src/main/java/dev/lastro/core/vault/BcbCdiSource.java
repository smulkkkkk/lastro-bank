package dev.lastro.core.vault;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** API pública do Banco Central, série 12. Não envia nenhum dado do cliente. */
@Component
class BcbCdiSource implements CdiSource {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper json;

    BcbCdiSource(JsonMapper json) {
        this.json = json;
    }

    @Override
    public List<Rate> between(LocalDate from, LocalDate to) {
        URI uri = URI.create("https://api.bcb.gov.br/dados/serie/bcdata.sgs.12/dados?formato=json&dataInicial="
                + from.format(DATE) + "&dataFinal=" + to.format(DATE));
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).build(), BodyHandlers.ofString());
            // Período sem dia útil publicado: o Banco Central responde 404.
            if (response.statusCode() == 404) {
                return List.of();
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Banco Central respondeu " + response.statusCode());
            }
            return parse(json, response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Recusa a resposta inteira se algo estiver fora do formato: taxa errada viraria dinheiro errado. */
    static List<Rate> parse(JsonMapper json, String body) {
        List<Rate> rates = new ArrayList<>();
        JsonNode root = json.readTree(body);
        if (!root.isArray()) {
            throw new IllegalStateException("Resposta inesperada do Banco Central");
        }
        for (JsonNode item : root) {
            if (!item.path("data").isString() || !item.path("valor").isString()) {
                throw new IllegalStateException("Taxa sem data ou valor");
            }
            BigDecimal percent = new BigDecimal(item.get("valor").asString());
            if (percent.signum() <= 0 || percent.compareTo(BigDecimal.ONE) >= 0) {
                throw new IllegalStateException("Taxa diária fora do esperado: " + percent);
            }
            rates.add(new Rate(LocalDate.parse(item.get("data").asString(), DATE), percent));
        }
        return rates;
    }
}
