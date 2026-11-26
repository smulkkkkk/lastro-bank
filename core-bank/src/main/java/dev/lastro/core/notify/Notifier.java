package dev.lastro.core.notify;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Enfileira um aviso para o cliente na transação de quem chama. O aviso só sai se a transação
 * confirmar: um Pix desfeito nunca avisa "Pix recebido".
 */
@Service
public class Notifier {

    public enum Kind { PIX_RECEIVED, PIX_SENT, PIX_REVIEW, PIX_DENIED, PIX_CANCELLED, SUPPORT }

    /** O formato está em contracts/notification-event.json. */
    record Event(UUID id, UUID userId, Kind kind, String title, String body, String link, Instant createdAt) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    Notifier(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public void send(UUID userId, Kind kind, String title, String body, String link) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Event event = new Event(UUID.randomUUID(), userId, kind, cut(title, 80), cut(body, 280), link, now);
        jdbc.sql("""
                        INSERT INTO core.outbox (id, kind, aggregate_id, payload, available_at, created_at)
                        VALUES (:id, 'NOTIFICATION', :id, CAST(:payload AS jsonb), :at, :at)""")
                .param("id", event.id()).param("payload", json.writeValueAsString(event))
                .param("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    /** "R$ 1.234,56", sem o espaço inquebrável que o NumberFormat põe. */
    public static String money(long cents) {
        String reais = String.format("%,d", cents / 100).replace(',', '.');
        return "R$ " + reais + "," + String.format("%02d", cents % 100);
    }

    private static String cut(String text, int max) {
        return text.codePointCount(0, text.length()) <= max ? text
                : text.substring(0, text.offsetByCodePoints(0, max - 1)) + "…";
    }
}
