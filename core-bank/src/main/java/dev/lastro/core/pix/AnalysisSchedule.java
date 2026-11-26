package dev.lastro.core.pix;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "lastro.sentinel.worker", havingValue = "true")
class AnalysisSchedule {

    private final AnalysisWorker worker;

    AnalysisSchedule(AnalysisWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "PT0.5S")
    void run() {
        worker.runOnce();
    }
}
