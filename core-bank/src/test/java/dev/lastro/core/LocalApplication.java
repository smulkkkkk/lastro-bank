package dev.lastro.core;

import dev.lastro.core.support.LocalPostgres;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;

/** Sobe a API com PostgreSQL embutido: {@code mvnw spring-boot:test-run}. */
public class LocalApplication {

    public static void main(String[] args) {
        Path data = Path.of(".local", "postgres").toAbsolutePath();
        System.setProperty("spring.datasource.url", LocalPostgres.persistentUrl(data, 5433));
        System.setProperty("lastro.security.allow-generated-key", "true");
        System.setProperty("lastro.sentinel.secret", LocalPostgres.DEV_SENTINEL_SECRET);
        System.setProperty("lastro.dev.analyst-email", "analista@lastro.dev");
        System.setProperty("logging.structured.format.console", "");
        SpringApplication.from(CoreBankApplication::main).run(args);
    }
}
