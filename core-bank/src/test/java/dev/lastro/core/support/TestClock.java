package dev.lastro.core.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/** Relógio que o teste acerta. Começa ao meio-dia de São Paulo, fora do limite noturno. */
public class TestClock extends Clock {

    public static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private volatile Instant now;

    public TestClock() {
        reset();
    }

    public void reset() {
        set(ZonedDateTime.now(SAO_PAULO).with(LocalTime.NOON));
    }

    public void set(ZonedDateTime time) {
        now = time.toInstant();
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(now, zone);
    }
}
