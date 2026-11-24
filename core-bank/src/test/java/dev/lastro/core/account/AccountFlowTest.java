package dev.lastro.core.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.lastro.core.support.IntegrationTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AccountFlowTest extends IntegrationTest {

    private Customer customer;

    @BeforeEach
    void setUp() throws Exception {
        customer = newCustomer();
    }

    @Test
    void depositIncreasesBalance() throws Exception {
        assertThat(deposit(customer, customer.accountId(), key(), "25050").getStatus()).isEqualTo(200);
        assertThat(balance()).isEqualTo(1_000_00 + 250_50);
    }

    @Test
    void repeatedDepositIsAppliedOnce() throws Exception {
        String key = key();
        var first = body(deposit(customer, customer.accountId(), key, "10000"));
        var second = body(deposit(customer, customer.accountId(), key, "10000"));
        assertThat(second).isEqualTo(first);
        assertThat(balance()).isEqualTo(1_100_00);

        var different = deposit(customer, customer.accountId(), key, "20000");
        assertThat(different.getStatus()).isEqualTo(409);
        assertThat(body(different).get("code").asString()).isEqualTo("idempotency.mismatch");
    }

    @Test
    void simultaneousEqualDepositsCreateOne() throws Exception {
        String key = key();
        List<JsonNode> responses = contend(customer.accountId(), 8,
                () -> body(deposit(customer, customer.accountId(), key, "10000")));

        assertThat(responses).allMatch(response -> response.has("transactionId"));
        assertThat(new HashSet<>(responses)).hasSize(1);
        assertThat(balance()).isEqualTo(1_100_00);
    }

    @Test
    void dailyLimitHoldsUnderConcurrency() throws Exception {
        List<Integer> statuses = contend(customer.accountId(), 8,
                () -> deposit(customer, customer.accountId(), key(), "100000").getStatus());

        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(5);
        assertThat(statuses.stream().filter(s -> s == 422)).hasSize(3);
        assertThat(balance()).isEqualTo(6_000_00);
    }

    @Test
    void invalidAmountsAndKeysAreRejected() throws Exception {
        for (String amount : new String[] {"0", "-1", "10.9", "500001", "null", "\"abc\""}) {
            var response = deposit(customer, customer.accountId(), key(), amount);
            assertThat(response.getStatus()).as(amount).isEqualTo(400);
            assertThat(body(response).get("code").asString()).isEqualTo("request.invalid");
        }
        assertThat(deposit(customer, customer.accountId(), "curta", "100").getStatus()).isEqualTo(400);
        assertThat(balance()).isEqualTo(1_000_00);
    }

    @Test
    void otherCustomersAccountLooksNonexistent() throws Exception {
        Customer other = newCustomer();
        assertThat(deposit(other, customer.accountId(), key(), "100").getStatus()).isEqualTo(404);
        for (String path : new String[] {"", "/statement"}) {
            var response = mvc.perform(get("/api/v1/accounts/" + customer.accountId() + path)
                    .header("Authorization", other.bearer())).andReturn().getResponse();
            assertThat(response.getStatus()).as(path).isEqualTo(404);
        }
        assertThat(balance()).isEqualTo(1_000_00);
    }

    @Test
    void statementPagesWithoutGapsAndKeepsRunningBalance() throws Exception {
        for (int i = 1; i <= 5; i++) {
            deposit(customer, customer.accountId(), key(), String.valueOf(i * 100));
        }
        List<Long> balances = new ArrayList<>();
        String cursor = "";
        int pages = 0;
        do {
            JsonNode page = statement("limit=2" + cursor);
            page.get("items").forEach(line -> balances.add(line.get("balanceAfterCents").asLong()));
            cursor = page.get("nextCursor").isNull() ? null : "&before=" + page.get("nextCursor").asLong();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(balances).containsExactly(101_500L, 101_000L, 100_600L, 100_300L, 100_100L, 100_000L);
    }

    @Test
    void filterDoesNotChangeRunningBalance() throws Exception {
        deposit(customer, customer.accountId(), key(), "700");
        JsonNode items = statement("kind=DEPOSIT").get("items");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).get("balanceAfterCents").asLong()).isEqualTo(1_007_00);
        assertThat(statement("from=2099-01-01T00:00:00Z").get("items").size()).isZero();
        assertThat(statement("to=2099-01-01T00:00:00Z").get("items").size()).isEqualTo(2);
    }

    @Test
    void badStatementParametersAreRejected() throws Exception {
        for (String query : new String[] {"limit=0", "limit=101", "kind=NADA", "from=ontem"}) {
            var response = mvc.perform(get("/api/v1/accounts/" + customer.accountId() + "/statement?" + query)
                    .header("Authorization", customer.bearer())).andReturn().getResponse();
            assertThat(response.getStatus()).as(query).isEqualTo(400);
        }
    }

    private JsonNode statement(String query) throws Exception {
        return body(mvc.perform(get("/api/v1/accounts/" + customer.accountId() + "/statement?" + query)
                .header("Authorization", customer.bearer())).andReturn().getResponse());
    }

    private long balance() {
        return jdbc.sql("SELECT balance_cents FROM core.account WHERE id = :id")
                .param("id", customer.accountId()).query(Long.class).single();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
