package dev.lastro.core.vault;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Roda de hora em hora, porque o Banco Central não publica a taxa sempre no mesmo horário. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "lastro.cdi.schedule", havingValue = "true")
class YieldSchedule {

    private final YieldService yields;

    YieldSchedule(YieldService yields) {
        this.yields = yields;
    }

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "PT1H")
    void run() {
        yields.run();
    }
}
