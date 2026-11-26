package dev.lastro.core.notify;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "lastro.sentinel.worker", havingValue = "true")
class DeliverySchedule {

    private final DeliveryWorker worker;

    DeliverySchedule(DeliveryWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "PT0.5S")
    void run() {
        worker.runOnce();
    }
}
