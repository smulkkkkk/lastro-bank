package dev.lastro.core.pix;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PixRepository {

    record Key(UUID id, String kind, String value) {
    }

    record Holder(UUID accountId, String name, String email, String cpf) {
    }

    record Receipt(UUID id, String key, String recipientName, long amountCents, String message,
            OffsetDateTime createdAt, String status) {
    }

    record Contact(String key, String name) {
    }

    record Usage(long dayCents, long nightCents, boolean night) {
    }

    private static final RowMapper<Key> KEY = (rs, n) ->
            new Key(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("value"));

    private static final RowMapper<Holder> HOLDER = (rs, n) -> new Holder(rs.getObject("account_id", UUID.class),
            rs.getString("full_name"), rs.getString("email"), rs.getString("cpf"));

    static final RowMapper<Receipt> RECEIPT = (rs, n) -> new Receipt(rs.getObject("id", UUID.class),
            rs.getString("key_used"), rs.getString("recipient_name"), rs.getLong("amount_cents"),
            rs.getString("message"), rs.getObject("created_at", OffsetDateTime.class), rs.getString("status"));

    private static final String HOLDER_COLUMNS = """
            SELECT a.id AS account_id, u.full_name, u.email, u.cpf
              FROM core.account a JOIN core.app_user u ON u.id = a.owner_id AND a.kind = 'CHECKING'\s""";

    private final JdbcClient jdbc;

    PixRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Holder holder(UUID ownerId) {
        return jdbc.sql(HOLDER_COLUMNS + "WHERE a.owner_id = :owner")
                .param("owner", ownerId).query(HOLDER).single();
    }

    Optional<Holder> findByKey(String value) {
        return jdbc.sql(HOLDER_COLUMNS + "JOIN core.pix_key k ON k.account_id = a.id WHERE k.value = :value")
                .param("value", value).query(HOLDER).optional();
    }

    List<Key> keys(UUID accountId) {
        return jdbc.sql("SELECT id, kind, value FROM core.pix_key WHERE account_id = :account ORDER BY created_at, id")
                .param("account", accountId).query(KEY).list();
    }

    void insertKey(Key key, UUID accountId) {
        jdbc.sql("INSERT INTO core.pix_key (id, account_id, kind, value) VALUES (:id, :account, :kind, :value)")
                .param("id", key.id()).param("account", accountId)
                .param("kind", key.kind()).param("value", key.value())
                .update();
    }

    boolean deleteKey(UUID keyId, UUID accountId) {
        return jdbc.sql("DELETE FROM core.pix_key WHERE id = :id AND account_id = :account")
                .param("id", keyId).param("account", accountId).update() == 1;
    }

    Optional<Receipt> findTransfer(UUID fromAccount, String idempotencyKey) {
        return jdbc.sql("SELECT * FROM core.pix_transfer WHERE from_account = :account AND idempotency_key = :key")
                .param("account", fromAccount).param("key", idempotencyKey)
                .query(RECEIPT).optional();
    }

    Optional<Receipt> findTransfer(UUID id, UUID fromAccount, boolean any) {
        return jdbc.sql("SELECT * FROM core.pix_transfer WHERE id = :id AND from_account = :account")
                .param("id", id).param("account", fromAccount).query(RECEIPT).optional();
    }

    /** Reserva sem lançar: o saldo continua o mesmo, mas a parte reservada não pode ser usada. */
    void hold(UUID accountId, long cents) {
        jdbc.sql("UPDATE core.account SET held_cents = held_cents + :cents WHERE id = :id")
                .param("cents", cents).param("id", accountId).update();
    }

    void insertPending(Receipt receipt, UUID from, UUID to, String idempotencyKey) {
        jdbc.sql("""
                        INSERT INTO core.pix_transfer (id, from_account, to_account, key_used, recipient_name,
                                                       amount_cents, message, idempotency_key, created_at, status)
                        VALUES (:id, :from, :to, :key, :name, :amount, :message, :idempotency, :at, 'PENDING')""")
                .param("id", receipt.id()).param("from", from).param("to", to)
                .param("key", receipt.key()).param("name", receipt.recipientName())
                .param("amount", receipt.amountCents()).param("message", receipt.message())
                .param("idempotency", idempotencyKey).param("at", receipt.createdAt())
                .update();
        jdbc.sql("""
                        INSERT INTO core.outbox (id, kind, aggregate_id, available_at, created_at)
                        VALUES (:id, 'PIX_ANALYSIS', :transfer, :at, :at)""")
                .param("id", UUID.randomUUID()).param("transfer", receipt.id()).param("at", receipt.createdAt())
                .update();
    }

    Usage usage(UUID accountId, OffsetDateTime at) {
        return jdbc.sql("SELECT * FROM core.pix_usage(:account, :at)")
                .param("account", accountId).param("at", at)
                .query((rs, n) -> new Usage(rs.getLong("day_cents"), rs.getLong("night_cents"), rs.getBoolean("is_night")))
                .single();
    }

    /** Últimas pessoas que de fato receberam, uma vez cada, só por chaves que ainda existem. */
    List<Contact> contacts(UUID accountId) {
        return jdbc.sql("""
                        SELECT key_used, recipient_name FROM (
                            SELECT DISTINCT ON (p.to_account) p.key_used, p.recipient_name, p.created_at
                              FROM core.pix_transfer p
                              JOIN core.pix_key k ON k.value = p.key_used AND k.account_id = p.to_account
                             WHERE p.from_account = :account AND p.status = 'SETTLED'
                             ORDER BY p.to_account, p.created_at DESC) latest
                         ORDER BY created_at DESC LIMIT 5""")
                .param("account", accountId)
                .query((rs, n) -> new Contact(rs.getString("key_used"), rs.getString("recipient_name")))
                .list();
    }
}
