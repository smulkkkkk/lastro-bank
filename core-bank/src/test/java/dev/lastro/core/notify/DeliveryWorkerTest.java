package dev.lastro.core.notify;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lastro.core.support.IntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DeliveryWorkerTest extends IntegrationTest {

    @Autowired private Notifier notifier;

    private final List<String> delivered = new ArrayList<>();
    private final AtomicBoolean down = new AtomicBoolean();
    private UUID user;

    private final OutboxChannel channel = new OutboxChannel() {
        @Override
        public String kind() {
            return "NOTIFICATION";
        }

        @Override
        public void deliver(UUID id, String payload) {
            if (down.get()) {
                throw new IllegalStateException("fora do ar");
            }
            delivered.add(payload);
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("DELETE FROM core.outbox WHERE kind = 'NOTIFICATION'").update();
        user = newCustomer().userId();
    }

    private DeliveryWorker worker(Clock at) {
        return new DeliveryWorker(jdbc, List.of(channel), at);
    }

    private int queued() {
        return jdbc.sql("SELECT count(*) FROM core.outbox WHERE kind = 'NOTIFICATION'").query(Integer.class).single();
    }

    @Test
    void deliversAndRemovesFromTheQueue() {
        notifier.send(user, Notifier.Kind.PIX_RECEIVED, "Pix recebido", "R$ 10,00 de Ana", "/app/extrato");
        assertThat(worker(clock).runOnce()).isEqualTo(1);
        assertThat(delivered).singleElement().satisfies(p -> assertThat(p).contains("\"title\": \"Pix recebido\""));
        assertThat(queued()).isZero();
        assertThat(worker(clock).runOnce()).as("não entrega duas vezes").isZero();
    }

    @Test
    void failureWaitsBeforeTryingAgain() {
        notifier.send(user, Notifier.Kind.PIX_SENT, "Pix enviado", "R$ 10,00 para Bia", null);
        down.set(true);
        assertThat(worker(clock).runOnce()).isZero();
        down.set(false);
        assertThat(worker(clock).runOnce()).as("logo em seguida ainda está esperando").isZero();
        Clock later = Clock.fixed(clock.instant().plusSeconds(3), ZoneOffset.UTC);
        assertThat(worker(later).runOnce()).isEqualTo(1);
    }

    @Test
    void givesUpAfterADay() {
        notifier.send(user, Notifier.Kind.PIX_SENT, "Pix enviado", "R$ 10,00 para Bia", null);
        down.set(true);
        Instant tomorrow = clock.instant().plus(DeliveryWorker.GIVE_UP_AFTER).plusSeconds(1);
        worker(Clock.fixed(tomorrow, ZoneOffset.UTC)).runOnce();
        assertThat(queued()).isZero();
    }

    @Test
    void longTextIsCutToTheContractLimits() {
        notifier.send(user, Notifier.Kind.SUPPORT, "t".repeat(200), "b".repeat(1000), null);
        worker(clock).runOnce();
        var event = json.readTree(delivered.getFirst());
        assertThat(event.get("title").asString().codePointCount(0, event.get("title").asString().length())).isEqualTo(80);
        assertThat(event.get("body").asString()).hasSize(280).endsWith("…");
    }

    @Test
    void moneyIsWrittenTheBrazilianWay() {
        assertThat(Notifier.money(5)).isEqualTo("R$ 0,05");
        assertThat(Notifier.money(123_456_78)).isEqualTo("R$ 123.456,78");
    }
}
