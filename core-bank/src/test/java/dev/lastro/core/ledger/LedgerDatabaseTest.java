package dev.lastro.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.lastro.core.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Fala SQL direto, sem passar pelo Java: as regras precisam valer no próprio banco. */
class LedgerDatabaseTest extends IntegrationTest {

    private static final UUID FUNDING = LedgerService.FUNDING_ACCOUNT;
    private UUID account;

    @BeforeEach
    void setUp() throws Exception {
        account = newCustomer().accountId();
    }

    @Test
    void rejectsUnbalancedTransactionOnCommit() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(s -> {
            UUID tx = openTransaction();
            entry(tx, FUNDING, "DEBIT", 100);
            entry(tx, account, "CREDIT", 101);
        })).hasStackTraceContaining("unbalanced");
        assertThat(balance()).isEqualTo(1_000_00);
    }

    @Test
    void rejectsTransactionWithoutEntries() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(s -> openTransaction()))
                .hasStackTraceContaining("unbalanced");
    }

    @Test
    void rejectsNewEntriesInClosedTransaction() {
        UUID tx = transaction.execute(s -> {
            UUID id = openTransaction();
            entry(id, FUNDING, "DEBIT", 100);
            entry(id, account, "CREDIT", 100);
            return id;
        });
        assertThatThrownBy(() -> transaction.executeWithoutResult(s -> {
            entry(tx, FUNDING, "DEBIT", 50);
            entry(tx, account, "CREDIT", 50);
        })).hasStackTraceContaining("is closed");
        assertThat(balance()).isEqualTo(1_000_00 + 100);
    }

    @Test
    void ledgerIsAppendOnly() {
        for (String sql : new String[] {
                "UPDATE core.ledger_entry SET amount_cents = 1",
                "DELETE FROM core.ledger_entry",
                "TRUNCATE core.ledger_entry",
                "UPDATE core.ledger_transaction SET description = 'x'",
                "DELETE FROM core.ledger_transaction",
                "TRUNCATE core.ledger_transaction CASCADE",
                "TRUNCATE core.account CASCADE"}) {
            assertThatThrownBy(() -> jdbc.sql(sql).update()).as(sql).hasStackTraceContaining("append-only");
        }
    }

    @Test
    void balanceOnlyChangesThroughEntries() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE core.account SET balance_cents = 999999 WHERE id = :id")
                .param("id", account).update()).hasStackTraceContaining("only change through ledger_entry");
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO core.account (id, kind, number, balance_cents)
                VALUES (gen_random_uuid(), 'SYSTEM', 'X', 10)""").update())
                .hasStackTraceContaining("zero balance");
    }

    @Test
    void customerBalanceNeverGoesNegative() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(s -> {
            UUID tx = openTransaction();
            entry(tx, account, "DEBIT", 1_000_01);
            entry(tx, FUNDING, "CREDIT", 1_000_01);
        })).hasStackTraceContaining("account_balance_check");
    }

    @Test
    void heldMoneyCannotBeSpent() {
        jdbc.sql("UPDATE core.account SET held_cents = 90000 WHERE id = :id").param("id", account).update();
        assertThatThrownBy(() -> transaction.executeWithoutResult(s -> {
            UUID tx = openTransaction();
            entry(tx, account, "DEBIT", 100_01);
            entry(tx, FUNDING, "CREDIT", 100_01);
        })).hasStackTraceContaining("account_balance_check");
    }

    private UUID openTransaction() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO core.ledger_transaction (id, kind, description) VALUES (:id, 'REVERSAL', 'teste')")
                .param("id", id).update();
        return id;
    }

    private void entry(UUID tx, UUID accountId, String direction, long cents) {
        jdbc.sql("""
                        INSERT INTO core.ledger_entry (transaction_id, account_id, direction, amount_cents)
                        VALUES (:tx, :account, :direction, :cents)""")
                .param("tx", tx).param("account", accountId).param("direction", direction).param("cents", cents)
                .update();
    }

    private long balance() {
        return jdbc.sql("SELECT balance_cents FROM core.account WHERE id = :id")
                .param("id", account).query(Long.class).single();
    }
}
