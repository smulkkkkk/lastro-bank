package dev.lastro.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lastro.core.ledger.LedgerService.Kind;
import dev.lastro.core.shared.ApiException;
import dev.lastro.core.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LedgerConcurrencyTest extends IntegrationTest {

    @Autowired private LedgerService ledger;

    @Test
    void crossedMovementsKeepTheTotalAndDoNotDeadlock() throws Exception {
        UUID a = newCustomer().accountId();
        UUID b = newCustomer().accountId();

        long done = runInParallel(200, i -> move(i % 2 == 0 ? a : b, i % 2 == 0 ? b : a, 100 + i));

        assertThat(done).isEqualTo(200);
        assertThat(balance(a) + balance(b)).isEqualTo(2_000_00);
    }

    @Test
    void sameMoneyIsNotSpentTwice() throws Exception {
        UUID from = newCustomer().accountId();
        UUID to = newCustomer().accountId();

        // R$ 1.000,00 de saldo e 20 tentativas de R$ 300,00: só cabem 3.
        long done = runInParallel(20, i -> move(from, to, 300_00));

        assertThat(done).isEqualTo(3);
        assertThat(balance(from)).isEqualTo(100_00);
        assertThat(balance(to)).isEqualTo(1_900_00);
    }

    private boolean move(UUID from, UUID to, long cents) {
        try {
            transaction.executeWithoutResult(s -> ledger.post(Kind.TRANSFER, "teste", null,
                    List.of(Posting.debit(from, cents), Posting.credit(to, cents))));
            return true;
        } catch (ApiException e) {
            assertThat(e.code()).isEqualTo("ledger.insufficient_funds");
            return false;
        }
    }

    private interface Attempt {
        boolean run(int index);
    }

    private static long runInParallel(int count, Attempt attempt) throws Exception {
        long succeeded = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                results.add(pool.submit(() -> attempt.run(index)));
            }
            for (Future<Boolean> result : results) {
                if (result.get(60, TimeUnit.SECONDS)) {
                    succeeded++;
                }
            }
        }
        return succeeded;
    }

    private long balance(UUID id) {
        return jdbc.sql("SELECT balance_cents FROM core.account WHERE id = :id")
                .param("id", id).query(Long.class).single();
    }
}
