package dev.lastro.core.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.lastro.core.ledger.LedgerService;
import dev.lastro.core.ledger.LedgerService.Kind;
import dev.lastro.core.ledger.Posting;
import dev.lastro.core.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class TraceTest extends IntegrationTest {

    @Autowired private LedgerService ledger;
    private Customer customer;

    @BeforeEach
    void setUp() throws Exception {
        customer = newCustomer(); // começa com R$ 1.000,00
    }

    @Test
    void spendingComesFromTheOldestMoneyFirst() throws Exception {
        long welcome = lastEntry();
        long second = receive(300_00);
        long third = receive(500_00);
        long small = spend(400_00);
        long big = spend(1_100_00);

        assertThat(parts(small)).containsExactly(part(welcome, 400_00));
        assertThat(parts(big)).containsExactly(
                part(welcome, 600_00), part(second, 300_00), part(third, 200_00));
    }

    @Test
    void incomeShowsWhereItWentAndWhatIsLeft() throws Exception {
        long welcome = lastEntry();
        long second = receive(300_00);
        long first = spend(900_00);
        long last = spend(250_00);

        JsonNode trace = trace(customer, welcome);
        assertThat(trace.get("direction").asString()).isEqualTo("CREDIT");
        assertThat(parts(welcome)).containsExactly(part(first, 900_00), part(last, 100_00));
        assertThat(trace.get("remainingCents").asLong()).isZero();
        assertThat(trace(customer, second).get("remainingCents").asLong()).isEqualTo(150_00);
        assertThat(trace(customer, last).get("remainingCents").asLong()).as("saída não sobra").isZero();
    }

    @Test
    void untouchedIncomeHasNoDestination() throws Exception {
        long entry = receive(50_00);
        assertThat(parts(entry)).isEmpty();
        assertThat(trace(customer, entry).get("remainingCents").asLong()).isEqualTo(50_00);
    }

    /** Vale para qualquer história: cada saída é explicada por inteiro, e só por dinheiro anterior. */
    @Test
    void everyCentOfEveryRandomHistoryIsExplained() throws Exception {
        Random random = new Random(20261124);
        long balance = 1_000_00;
        List<Long> spent = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            if (balance > 0 && random.nextBoolean()) {
                long cents = 1 + random.nextLong(balance);
                spent.add(spend(cents));
                balance -= cents;
            } else {
                long cents = 1 + random.nextLong(200_00);
                receive(cents);
                balance += cents;
            }
        }
        for (long entry : spent) {
            long amount = jdbc.sql("SELECT amount_cents FROM core.ledger_entry WHERE id = :id")
                    .param("id", entry).query(Long.class).single();
            List<String> parts = parts(entry);
            assertThat(parts.stream().mapToLong(p -> Long.parseLong(p.split(":")[1])).sum()).isEqualTo(amount);
            assertThat(parts).allMatch(p -> Long.parseLong(p.split(":")[0]) < entry);
            assertThat(parts).allMatch(p -> Long.parseLong(p.split(":")[1]) > 0);
        }
        long remaining = jdbc.sql("""
                        SELECT sum(e.amount_cents - coalesce((SELECT sum(part_cents) FROM core.trace_entry(e.id)), 0))
                          FROM core.ledger_entry e WHERE e.account_id = :id AND e.direction = 'CREDIT'""")
                .param("id", customer.accountId()).query(Long.class).single();
        assertThat(remaining).as("o que sobrou das entradas é o saldo").isEqualTo(balance);
    }

    @Test
    void otherCustomersEntriesAreInvisible() throws Exception {
        Customer other = newCustomer();
        long entry = jdbc.sql("SELECT max(id) FROM core.ledger_entry WHERE account_id = :id")
                .param("id", other.accountId()).query(Long.class).single();
        for (String path : new String[] {
                customer.accountId() + "/entries/" + entry, other.accountId() + "/entries/" + entry}) {
            assertThat(mvc.perform(get("/api/v1/accounts/" + path + "/trace")
                    .header("Authorization", customer.bearer())).andReturn().getResponse().getStatus())
                    .as(path).isEqualTo(404);
        }
    }

    private static String part(long entry, long cents) {
        return entry + ":" + cents;
    }

    private List<String> parts(long entry) throws Exception {
        List<String> parts = new ArrayList<>();
        trace(customer, entry).get("items").forEach(item ->
                parts.add(part(item.get("entryId").asLong(), item.get("partCents").asLong())));
        return parts;
    }

    private JsonNode trace(Customer owner, long entry) throws Exception {
        return body(mvc.perform(get("/api/v1/accounts/" + owner.accountId() + "/entries/" + entry + "/trace")
                .header("Authorization", owner.bearer())).andReturn().getResponse());
    }

    private long receive(long cents) {
        return post(Posting.debit(LedgerService.FUNDING_ACCOUNT, cents), Posting.credit(customer.accountId(), cents));
    }

    private long spend(long cents) {
        return post(Posting.debit(customer.accountId(), cents), Posting.credit(LedgerService.MERCHANTS_ACCOUNT, cents));
    }

    private long post(Posting... postings) {
        transaction.executeWithoutResult(s ->
                ledger.post(Kind.REVERSAL, "teste", UUID.randomUUID().toString(), List.of(postings)));
        return lastEntry();
    }

    private long lastEntry() {
        return jdbc.sql("SELECT max(id) FROM core.ledger_entry WHERE account_id = :id")
                .param("id", customer.accountId()).query(Long.class).single();
    }
}
