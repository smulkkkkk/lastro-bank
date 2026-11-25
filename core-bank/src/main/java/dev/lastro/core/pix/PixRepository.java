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
            OffsetDateTime createdAt) {
    }

    record Contact(String key, String name) {
    }

    record Usage(long dayCents, long nightCents, boolean night) {
    }

    private static final RowMapper<Key> KEY = (rs, n) ->
            new Key(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("value"));

    private static final RowMapper<Holder> HOLDER = (rs, n) -> new Holder(rs.getObject("account_id", UUID.class),
            rs.getString("full_name"), rs.getString("email"), rs.getString("cpf"));

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
        return jdbc.sql("""
                        SELECT id, key_used, recipient_name, amount_cents, message, created_at
                          FROM core.pix_transfer WHERE from_account = :account AND idempotency_key = :key""")
                .param("account", fromAccount).param("key", idempotencyKey)
                .query((rs, n) -> new Receipt(rs.getObject("id", UUID.class), rs.getString("key_used"),
                        rs.getString("recipient_name"), rs.getLong("amount_cents"), rs.getString("message"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .optional();
    }

    void insertTransfer(Receipt receipt, UUID from, UUID to, String idempotencyKey, UUID transactionId) {
        jdbc.sql("""
                        INSERT INTO core.pix_transfer (id, from_account, to_account, key_used, recipient_name,
                                                       amount_cents, message, idempotency_key, transaction_id, created_at)
                        VALUES (:id, :from, :to, :key, :name, :amount, :message, :idempotency, :tx, :at)""")
                .param("id", receipt.id()).param("from", from).param("to", to)
                .param("key", receipt.key()).param("name", receipt.recipientName())
                .param("amount", receipt.amountCents()).param("message", receipt.message())
                .param("idempotency", idempotencyKey).param("tx", transactionId).param("at", receipt.createdAt())
                .update();
    }

    Usage usage(UUID accountId, OffsetDateTime at) {
        return jdbc.sql("SELECT * FROM core.pix_usage(:account, :at)")
                .param("account", accountId).param("at", at)
                .query((rs, n) -> new Usage(rs.getLong("day_cents"), rs.getLong("night_cents"), rs.getBoolean("is_night")))
                .single();
    }

    /** Últimas pessoas, uma vez cada, só por chaves que ainda existem. */
    List<Contact> contacts(UUID accountId) {
        return jdbc.sql("""
                        SELECT key_used, recipient_name FROM (
                            SELECT DISTINCT ON (p.to_account) p.key_used, p.recipient_name, p.created_at
                              FROM core.pix_transfer p
                              JOIN core.pix_key k ON k.value = p.key_used AND k.account_id = p.to_account
                             WHERE p.from_account = :account
                             ORDER BY p.to_account, p.created_at DESC) latest
                         ORDER BY created_at DESC LIMIT 5""")
                .param("account", accountId)
                .query((rs, n) -> new Contact(rs.getString("key_used"), rs.getString("recipient_name")))
                .list();
    }
}
