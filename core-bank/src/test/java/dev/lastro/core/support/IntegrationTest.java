package dev.lastro.core.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    protected static final String PASSWORD = "senha-forte-123";
    private static final EmbeddedPostgres POSTGRES = LocalPostgres.temporary();

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected TransactionTemplate transaction;
    @Autowired protected JsonMapper json;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> LocalPostgres.url(POSTGRES));
        registry.add("lastro.security.allow-generated-key", () -> "true");
        registry.add("logging.structured.format.console", () -> "");
    }

    /** Vale depois de qualquer teste: nenhum saldo pode divergir dos lançamentos. */
    @AfterEach
    void ledgerReconciles() {
        long differences = jdbc.sql("SELECT count(*) FROM core.v_reconciliation").query(Long.class).single();
        long total = jdbc.sql("SELECT coalesce(sum(balance_cents), 0) FROM core.account").query(Long.class).single();
        if (differences != 0 || total != 0) {
            throw new AssertionError("Razão não fecha: diferenças=" + differences + " soma=" + total);
        }
    }

    protected record Customer(String email, UUID userId, UUID accountId, String token, Cookie refresh) {
        public String bearer() {
            return "Bearer " + token;
        }
    }

    protected Customer newCustomer() throws Exception {
        String email = UUID.randomUUID() + "@teste.dev";
        JsonNode created = body(register(email, randomCpf(), PASSWORD));
        MockHttpServletResponse login = login(email, PASSWORD);
        UUID userId = UUID.fromString(created.get("userId").asString());
        UUID accountId = jdbc.sql("SELECT id FROM core.account WHERE owner_id = :id")
                .param("id", userId).query(UUID.class).single();
        return new Customer(email, userId, accountId, body(login).get("accessToken").asString(),
                login.getCookie("lastro_refresh"));
    }

    protected MockHttpServletResponse register(String email, String cpf, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName": "Cliente Teste", "email": "%s", "cpf": "%s", "password": "%s"}"""
                        .formatted(email, cpf, password)))
                .andReturn().getResponse();
    }

    protected MockHttpServletResponse login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "%s", "password": "%s"}""".formatted(email, password)))
                .andReturn().getResponse();
    }

    protected MockHttpServletResponse deposit(Customer customer, UUID accountId, String key, String amount)
            throws Exception {
        return mvc.perform(post("/api/v1/accounts/{id}/deposits", accountId)
                        .header("Authorization", customer.bearer())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\": " + amount + "}"))
                .andReturn().getResponse();
    }

    /**
     * Segura a conta até todas as tarefas estarem paradas em uma trava do banco, e só então libera.
     * Garante a disputa, em vez de torcer para as threads coincidirem. Máximo de 8 tarefas, porque
     * o pool tem 10 conexões.
     */
    protected <T> List<T> contend(UUID accountId, int tasks, Callable<T> task) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks)) {
            List<Future<T>> results = transaction.execute(s -> {
                jdbc.sql("SELECT id FROM core.account WHERE id = :id FOR UPDATE")
                        .param("id", accountId).query(UUID.class).single();
                List<Future<T>> started = new ArrayList<>();
                for (int i = 0; i < tasks; i++) {
                    started.add(pool.submit(task));
                }
                awaitBlocked(tasks);
                return started;
            });
            List<T> values = new ArrayList<>();
            for (Future<T> result : results) {
                values.add(result.get(60, TimeUnit.SECONDS));
            }
            return values;
        }
    }

    private void awaitBlocked(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        long blocked = 0;
        while (System.nanoTime() < deadline) {
            // Sem isto o PostgreSQL repete a mesma fotografia durante toda a transação.
            jdbc.sql("SELECT pg_stat_clear_snapshot()::text").query((rs, n) -> 0).list();
            blocked = jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'")
                    .query(Long.class).single();
            if (blocked == expected) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw new AssertionError("Esperava " + expected + " tarefas bloqueadas, havia " + blocked);
    }

    protected JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsByteArray());
    }

    /** CPF com dígitos válidos, gerado ao acaso. */
    protected static String randomCpf() {
        String digits = "%09d".formatted(ThreadLocalRandom.current().nextInt(1, 999_999_999));
        if (digits.chars().distinct().count() == 1) {
            return randomCpf();
        }
        for (int length = 9; length < 11; length++) {
            int sum = 0;
            for (int i = 0; i < length; i++) {
                sum += (digits.charAt(i) - '0') * (length + 1 - i);
            }
            int rest = sum * 10 % 11;
            digits += rest == 10 ? 0 : rest;
        }
        return digits;
    }
}
