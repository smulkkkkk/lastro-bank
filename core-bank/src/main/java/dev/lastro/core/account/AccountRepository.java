package dev.lastro.core.account;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class AccountRepository {

    record Account(UUID id, String kind, String branch, String number,
            long balanceCents, long heldCents, long availableCents) {
    }

    record StatementLine(long entryId, UUID transactionId, String kind, String description, String direction,
            long amountCents, long balanceAfterCents, OffsetDateTime createdAt) {
    }

    record TracePart(long entryId, String kind, String description, OffsetDateTime createdAt,
            long amountCents, long partCents) {
    }

    record TracedEntry(String direction, long amountCents) {
    }

    record PostedDeposit(UUID transactionId, long amountCents) {
    }

    private static final String COLUMNS =
            "SELECT id, kind, branch, number, balance_cents, held_cents FROM core.account ";

    private static final RowMapper<Account> ACCOUNT = (rs, n) -> {
        long balance = rs.getLong("balance_cents");
        long held = rs.getLong("held_cents");
        return new Account(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("branch"),
                rs.getString("number"), balance, held, balance - held);
    };

    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long nextNumber() {
        return jdbc.sql("SELECT nextval('core.account_number_seq')").query(Long.class).single();
    }

    void insert(UUID id, UUID ownerId, String kind, String number, long creditLimitCents) {
        jdbc.sql("""
                        INSERT INTO core.account (id, owner_id, kind, number, credit_limit_cents)
                        VALUES (:id, :owner, :kind, :number, :limit)""")
                .param("limit", creditLimitCents)
                .param("id", id)
                .param("owner", ownerId)
                .param("kind", kind)
                .param("number", number)
                .update();
    }

    /** Só para os tipos que existem uma vez por cliente. */
    UUID idByKind(UUID ownerId, String kind) {
        return jdbc.sql("SELECT id FROM core.account WHERE owner_id = :owner AND kind = :kind")
                .param("owner", ownerId)
                .param("kind", kind)
                .query(UUID.class)
                .single();
    }

    List<Account> findByOwner(UUID ownerId) {
        return jdbc.sql(COLUMNS + "WHERE owner_id = :owner AND kind IN ('CHECKING', 'VAULT') ORDER BY created_at, id")
                .param("owner", ownerId)
                .query(ACCOUNT)
                .list();
    }

    Optional<Account> findOwned(UUID id, UUID ownerId) {
        return jdbc.sql(COLUMNS + "WHERE id = :id AND owner_id = :owner")
                .param("id", id)
                .param("owner", ownerId)
                .query(ACCOUNT)
                .optional();
    }

    Optional<PostedDeposit> findDeposit(String externalRef, UUID accountId) {
        return jdbc.sql("""
                        SELECT t.id, e.amount_cents
                          FROM core.ledger_transaction t
                          JOIN core.ledger_entry e ON e.transaction_id = t.id AND e.account_id = :account
                         WHERE t.external_ref = :ref""")
                .param("account", accountId)
                .param("ref", externalRef)
                .query((rs, n) -> new PostedDeposit(rs.getObject("id", UUID.class), rs.getLong("amount_cents")))
                .optional();
    }

    /** O dia do limite é o do cliente, no fuso de São Paulo. */
    long depositedToday(UUID accountId) {
        return jdbc.sql("""
                        SELECT coalesce(sum(e.amount_cents), 0)
                          FROM core.ledger_entry e
                          JOIN core.ledger_transaction t ON t.id = e.transaction_id
                         WHERE e.account_id = :account AND t.kind = 'DEPOSIT'
                           AND e.created_at >= date_trunc('day', now() AT TIME ZONE 'America/Sao_Paulo')
                                               AT TIME ZONE 'America/Sao_Paulo'""")
                .param("account", accountId)
                .query(Long.class)
                .single();
    }

    Optional<TracedEntry> findEntry(UUID accountId, long entryId) {
        return jdbc.sql("SELECT direction, amount_cents FROM core.ledger_entry WHERE id = :id AND account_id = :account")
                .param("id", entryId)
                .param("account", accountId)
                .query((rs, n) -> new TracedEntry(rs.getString("direction"), rs.getLong("amount_cents")))
                .optional();
    }

    List<TracePart> trace(long entryId) {
        return jdbc.sql("SELECT * FROM core.trace_entry(:id)")
                .param("id", entryId)
                .query((rs, n) -> new TracePart(rs.getLong("entry_id"), rs.getString("kind"),
                        rs.getString("description"), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getLong("amount_cents"), rs.getLong("part_cents")))
                .list();
    }

    List<StatementLine> statement(UUID accountId, OffsetDateTime from, OffsetDateTime to, String kind,
            Long before, int limit) {
        return jdbc.sql("""
                        SELECT * FROM core.account_statement(:account, CAST(:from AS timestamptz),
                            CAST(:to AS timestamptz), CAST(:kind AS text), CAST(:before AS bigint), :limit)""")
                .param("account", accountId)
                .param("from", from)
                .param("to", to)
                .param("kind", kind)
                .param("before", before)
                .param("limit", limit)
                .query((rs, n) -> new StatementLine(rs.getLong("entry_id"),
                        rs.getObject("transaction_id", UUID.class), rs.getString("kind"),
                        rs.getString("description"), rs.getString("direction"), rs.getLong("amount_cents"),
                        rs.getLong("balance_after_cents"), rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }
}
