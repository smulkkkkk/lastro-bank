package dev.lastro.core.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.lastro.core.support.IntegrationTest;
import dev.lastro.core.support.TestClock;
import dev.lastro.core.vault.CdiSource.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;

class VaultFlowTest extends IntegrationTest {

    private static final String RATE = "0.050788";

    @MockitoBean private CdiSource source;
    @Autowired private YieldService yields;

    private Customer ana;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        ana = newCustomer();
        today = yields.today();
    }

    @AfterEach
    void forgetRates() {
        jdbc.sql("DELETE FROM core.vault_yield").update();
        jdbc.sql("DELETE FROM core.cdi_rate").update();
        jdbc.sql("UPDATE core.vault SET archived_on = created_on WHERE archived_on IS NULL").update();
    }

    @Test
    void savingAndRedeemingMoveMoneyBetweenAccounts() throws Exception {
        String id = create("Viagem", 500_00L, null);
        assertThat(move(id, "deposits", 300_00, id()).getStatus()).isEqualTo(200);
        assertThat(checking()).isEqualTo(700_00);
        assertThat(move(id, "withdrawals", 120_00, id()).getStatus()).isEqualTo(200);
        assertThat(checking()).isEqualTo(820_00);

        JsonNode overview = overview();
        assertThat(overview.get("totalCents").asLong()).isEqualTo(180_00);
        JsonNode vault = overview.get("items").get(0);
        assertThat(vault.get("name").asString()).isEqualTo("Viagem");
        assertThat(vault.get("cdiPercent").asInt()).isEqualTo(100);
        assertThat(vault.get("goalCents").asLong()).isEqualTo(500_00);
    }

    @Test
    void simultaneousEqualDepositsMoveMoneyOnce() throws Exception {
        String id = create("Reserva", null, null);
        String key = id();
        List<JsonNode> results = contend(ana.accountId(), 8, () -> body(move(id, "deposits", 100_00, key)));
        assertThat(results).allMatch(result -> result.has("id"));
        assertThat(new HashSet<>(results)).hasSize(1);
        assertThat(checking()).isEqualTo(900_00);
        assertThat(code(move(id, "deposits", 100_01, key))).isEqualTo("idempotency.mismatch");
    }

    @Test
    void cannotSaveOrRedeemMoreThanThereIs() throws Exception {
        String id = create("Reserva", null, null);
        assertThat(code(move(id, "deposits", 1_000_01, id()))).isEqualTo("ledger.insufficient_funds");
        move(id, "deposits", 10_00, id());
        assertThat(code(move(id, "withdrawals", 10_01, id()))).isEqualTo("ledger.insufficient_funds");
        assertThat(move(id, "deposits", 0, id()).getStatus()).isEqualTo(400);
        assertThat(move(id, "deposits", -1, id()).getStatus()).isEqualTo(400);
    }

    @Test
    void eachBusinessDayPaysOnceAndCompounds() throws Exception {
        String id = create("Reserva", null, null);
        move(id, "deposits", 1_000_00, id());
        rates(today, today.plusDays(1));
        dayIs(today.plusDays(2));

        yields.accrue();
        yields.accrue();

        // 50,788 + 50,788 (a 2ª sobre R$ 1.000,50, porque o centavo do 1º dia já entrou) = 101,60...
        assertThat(saved(id)).isEqualTo(1_000_00 + 101);
        assertThat(days(id)).isEqualTo(2);
        assertThat(overview().get("items").get(0).get("yieldCents").asLong()).isEqualTo(101);
    }

    @Test
    void fractionsOfACentAreNotLost() throws Exception {
        String id = create("Pouquinho", null, null);
        move(id, "deposits", 10_00, id()); // rende 0,50788 centavo por dia
        rates(today, today.plusDays(1), today.plusDays(2), today.plusDays(3));
        dayIs(today.plusDays(4));
        yields.accrue();
        assertThat(saved(id)).isEqualTo(10_02); // 4 x 0,50788 = 2,03
    }

    @Test
    void lockedVaultPaysMoreAndOnlyOpensOnTheDate() throws Exception {
        LocalDate date = today.plusDays(30);
        String id = create("Casa", null, date);
        move(id, "deposits", 1_000_00, id());
        assertThat(code(move(id, "withdrawals", 1_00, id()))).isEqualTo("vault.locked");
        rates(today);
        dayIs(today.plusDays(1));
        yields.accrue();
        assertThat(saved(id)).isEqualTo(1_000_00 + 55);

        dayIs(date.minusDays(1));
        assertThat(code(move(id, "withdrawals", 1_00, id()))).isEqualTo("vault.locked");
        dayIs(date);
        assertThat(move(id, "withdrawals", 1_00, id()).getStatus()).isEqualTo(200);
    }

    @Test
    void lockDateHasLimits() throws Exception {
        assertThat(code(createRaw("x", null, today.plusDays(29)))).isEqualTo("vault.lock_date");
        assertThat(code(createRaw("x", null, today.plusYears(5).plusDays(1)))).isEqualTo("vault.lock_date");
        assertThat(createRaw("x", null, today.plusYears(5)).getStatus()).isEqualTo(201);
    }

    @Test
    void openDayAndDaysWithoutRateDoNotPay() throws Exception {
        String id = create("Reserva", null, null);
        move(id, "deposits", 1_000_00, id());
        rates(today.minusDays(1), today);
        yields.accrue();
        assertThat(days(id)).as("hoje ainda não acabou e ontem ela não existia").isZero();

        dayIs(today.plusDays(5)); // só há taxa de hoje
        yields.accrue();
        assertThat(days(id)).isEqualTo(1);
    }

    @Test
    void simultaneousRunsPayOnce() throws Exception {
        String id = create("Reserva", null, null);
        move(id, "deposits", 1_000_00, id());
        rates(today);
        dayIs(today.plusDays(1));
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                runs.add(pool.submit(() -> yields.accrue()));
            }
            for (Future<?> run : runs) {
                run.get();
            }
        }
        assertThat(saved(id)).isEqualTo(1_000_00 + 50);
    }

    @Test
    void lateYieldOfAClosedVaultGoesToTheOwner() throws Exception {
        String id = create("Breve", null, null);
        move(id, "deposits", 1_000_00, id());
        dayIs(today.plusDays(1)); // a taxa de hoje ainda não saiu
        // O razão usa a hora real; para o resgate cair no dia seguinte, a base do dia é conferida antes dele.
        rates(today);
        jdbc.sql("UPDATE core.vault SET archived_on = :day WHERE account_id = :id")
                .param("day", today.plusDays(1)).param("id", UUID.fromString(id)).update();
        yields.accrue();
        assertThat(checking()).isEqualTo(50);
        assertThat(saved(id)).isEqualTo(1_000_00);
    }

    @Test
    void bancoCentralOfflineKeepsEverythingWorking() throws Exception {
        String id = create("Reserva", null, null);
        move(id, "deposits", 1_000_00, id());
        rates(today);
        dayIs(today.plusDays(1));
        Mockito.when(source.between(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("fora do ar"));
        yields.run();
        assertThat(saved(id)).as("paga o que já tem taxa").isEqualTo(1_000_00 + 50);

        Mockito.reset(source);
        Mockito.when(source.between(today.plusDays(1), today.plusDays(2)))
                .thenReturn(List.of(new Rate(today.plusDays(1), new BigDecimal(RATE))));
        dayIs(today.plusDays(2));
        yields.run();
        assertThat(days(id)).as("recupera o dia perdido").isEqualTo(2);
    }

    @Test
    void onlyEmptyVaultsCanBeDeleted() throws Exception {
        String id = create("Reserva", null, null);
        move(id, "deposits", 10_00, id());
        assertThat(code(remove(id))).isEqualTo("vault.not_empty");
        move(id, "withdrawals", 10_00, id());
        assertThat(remove(id).getStatus()).isEqualTo(204);
        assertThat(overview().get("items").size()).isZero();
        assertThat(code(move(id, "deposits", 10_00, id()))).isEqualTo("vault.not_found");
    }

    @Test
    void depositAndDeleteAtTheSameTimeNeverTrapMoney() throws Exception {
        for (int round = 0; round < 5; round++) {
            String id = create("Corrida", null, null);
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                Future<Integer> in = pool.submit(() -> move(id, "deposits", 1_00, id()).getStatus());
                Future<Integer> out = pool.submit(() -> remove(id).getStatus());
                in.get();
                out.get();
            }
            boolean archived = jdbc.sql("SELECT archived_on IS NOT NULL FROM core.vault WHERE account_id = :id")
                    .param("id", UUID.fromString(id)).query(Boolean.class).single();
            assertThat(archived && saved(id) > 0).as("dinheiro preso em caixinha excluída").isFalse();
        }
    }

    @Test
    void otherCustomersVaultsAreInvisible() throws Exception {
        String id = create("Minha", null, null);
        move(id, "deposits", 10_00, id());
        Customer owner = ana;
        ana = newCustomer();
        assertThat(code(move(id, "withdrawals", 1_00, id()))).isEqualTo("vault.not_found");
        assertThat(code(move(id, "deposits", 1_00, id()))).isEqualTo("vault.not_found");
        assertThat(code(remove(id))).isEqualTo("vault.not_found");
        assertThat(overview().get("items").size()).isZero();
        ana = owner;
        assertThat(saved(id)).isEqualTo(10_00);
    }

    @Test
    void thereIsAMaximumOfVaults() throws Exception {
        for (int i = 0; i < 10; i++) {
            create("Caixinha " + i, null, null);
        }
        assertThat(code(createRaw("Mais uma", null, null))).isEqualTo("vault.too_many");
        assertThat(createRaw(" ", null, null).getStatus()).isEqualTo(400);
        assertThat(createRaw("x".repeat(41), null, null).getStatus()).isEqualTo(400);
    }

    private void rates(LocalDate... days) {
        for (LocalDate day : days) {
            jdbc.sql("INSERT INTO core.cdi_rate (day, daily_percent) VALUES (:day, :rate)")
                    .param("day", day).param("rate", new BigDecimal(RATE)).update();
        }
    }

    private void dayIs(LocalDate day) {
        clock.set(day.atTime(LocalTime.NOON).atZone(TestClock.SAO_PAULO));
    }

    private String create(String name, Long goal, LocalDate lockedUntil) throws Exception {
        MockHttpServletResponse response = createRaw(name, goal, lockedUntil);
        assertThat(response.getStatus()).isEqualTo(201);
        return body(response).get("id").asString();
    }

    private MockHttpServletResponse createRaw(String name, Long goal, LocalDate lockedUntil) throws Exception {
        String date = lockedUntil == null ? "null" : "\"" + lockedUntil + "\"";
        return mvc.perform(post("/api/v1/vaults").header("Authorization", ana.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"%s\", \"goalCents\": %s, \"lockedUntil\": %s}".formatted(name, goal, date)))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse move(String vault, String way, long cents, String key) throws Exception {
        return mvc.perform(post("/api/v1/vaults/" + vault + "/" + way).header("Authorization", ana.bearer())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountCents\": " + cents + "}")).andReturn().getResponse();
    }

    private MockHttpServletResponse remove(String vault) throws Exception {
        return mvc.perform(delete("/api/v1/vaults/" + vault).header("Authorization", ana.bearer()))
                .andReturn().getResponse();
    }

    private JsonNode overview() throws Exception {
        return body(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/vaults").header("Authorization", ana.bearer())).andReturn().getResponse());
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return body(response).get("code").asString();
    }

    private long days(String vault) {
        return jdbc.sql("SELECT count(*) FROM core.vault_yield WHERE vault_id = :id")
                .param("id", UUID.fromString(vault)).query(Long.class).single();
    }

    private long saved(String vault) {
        return balance(UUID.fromString(vault));
    }

    private long checking() {
        return balance(ana.accountId());
    }

    private long balance(UUID account) {
        return jdbc.sql("SELECT balance_cents FROM core.account WHERE id = :id")
                .param("id", account).query(Long.class).single();
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
