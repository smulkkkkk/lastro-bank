package dev.lastro.core.helpdesk;

import dev.lastro.core.notify.OutboxChannel;
import dev.lastro.core.shared.Hmac;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Leva o pedido de atendimento ao webhook do n8n, que abre a conversa no WhatsApp. Sem webhook
 * configurado, o protocolo fica só no banco e o log registra.
 */
@Component
class N8nChannel implements OutboxChannel {

    private static final Logger log = LoggerFactory.getLogger(N8nChannel.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Clock clock;
    private final URI webhook;
    private final byte[] secret;

    N8nChannel(Clock clock, @Value("${lastro.helpdesk.n8n-webhook-url:}") String url,
            @Value("${lastro.helpdesk.n8n-webhook-secret:}") String secret) {
        this.clock = clock;
        this.webhook = url == null || url.isBlank() ? null : checked(URI.create(url.strip()));
        this.secret = secret == null || secret.isBlank() ? null : secret.getBytes(StandardCharsets.UTF_8);
    }

    /** HTTPS, ou HTTP só na própria máquina (n8n rodando local). O histórico tem dados do cliente. */
    private static URI checked(URI uri) {
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && local)) {
            throw new IllegalStateException("LASTRO_N8N_WEBHOOK_URL precisa ser https (ou http em localhost)");
        }
        return uri;
    }

    @Override
    public String kind() {
        return "SUPPORT_HANDOFF";
    }

    @Override
    public void deliver(UUID id, String payload) {
        if (webhook == null) {
            log.info("Atendimento {} registrado; defina LASTRO_N8N_WEBHOOK_URL para mandar ao WhatsApp", id);
            return;
        }
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        HttpRequest.Builder request = HttpRequest.newBuilder(webhook)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", id.toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (secret != null) {
            String timestamp = Long.toString(clock.instant().getEpochSecond());
            request.header("X-Lastro-Timestamp", timestamp).header("X-Lastro-Signature", Hmac.sign(secret, timestamp, body));
        }
        try {
            int status = http.send(request.build(), BodyHandlers.discarding()).statusCode();
            if (status / 100 != 2) {
                throw new IllegalStateException("n8n respondeu " + status);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
