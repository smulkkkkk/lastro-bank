package dev.lastro.core.reward;

import dev.lastro.core.account.AccountService;
import dev.lastro.core.ledger.LedgerService;
import dev.lastro.core.ledger.LedgerService.Kind;
import dev.lastro.core.ledger.Posting;
import dev.lastro.core.shared.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bolhas (cashback) e cupons das lojas parceiras. Uma bolha vale R$ 0,01. As bolhas não são
 * dinheiro até a troca: só então viram um lançamento no razão, saindo da conta de recompensas.
 */
@Service
public class RewardService {

    public static final UUID REWARDS_ACCOUNT = new UUID(0, 5);
    /** Fora das parceiras, toda compra no cartão devolve 1%. */
    static final int DEFAULT_CASHBACK_BP = 100;
    public static final long MIN_REDEEM = 100;
    static final Duration COUPON_LIFE = Duration.ofDays(7);

    public record UsableCoupon(UUID id, long discountCents) {
    }

    public record Coupon(UUID id, Instant expiresAt) {
    }

    public record Store(String merchant, String name, String category, String blurb, double cashbackPercent,
            int couponPercent, long couponCapCents, long couponMinCents, Coupon coupon) {
    }

    public record Entry(long bubbles, String reason, String description, Instant createdAt) {
    }

    public record Wallet(long bubbles, long valueCents, List<Entry> entries) {
    }

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final AccountService accounts;
    private final Clock clock;

    RewardService(JdbcClient jdbc, LedgerService ledger, AccountService accounts, Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.accounts = accounts;
        this.clock = clock;
    }

    /** O cupom ativo da loja, travado até o fim da compra. Compra abaixo do mínimo não usa o cupom. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UsableCoupon> usableCoupon(UUID ownerId, String merchant, long cents) {
        return jdbc.sql("""
                        SELECT c.id, s.coupon_percent, s.coupon_cap_cents, s.coupon_min_cents
                          FROM core.coupon c JOIN core.store s ON s.merchant = c.merchant
                         WHERE c.owner_id = :owner AND c.merchant = :merchant AND c.used_at IS NULL
                           AND c.expires_at > :now
                           FOR UPDATE OF c""")
                .param("owner", ownerId).param("merchant", merchant).param("now", now())
                .query((rs, n) -> {
                    long min = rs.getLong("coupon_min_cents");
                    long discount = Math.min(cents * rs.getInt("coupon_percent") / 100, rs.getLong("coupon_cap_cents"));
                    return new UsableCoupon(rs.getObject("id", UUID.class), cents < min ? 0 : discount);
                })
                .optional()
                .filter(coupon -> coupon.discountCents() > 0);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markUsed(UUID couponId, UUID transactionId) {
        jdbc.sql("UPDATE core.coupon SET used_at = :now, transaction_id = :tx WHERE id = :id")
                .param("now", now()).param("tx", transactionId).param("id", couponId).update();
    }

    /** Cashback de uma compra, sobre o que foi cobrado de fato. Devolve quantas bolhas rendeu. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long earn(UUID ownerId, String merchant, long chargedCents, UUID purchaseId, String storeName) {
        int bp = jdbc.sql("SELECT cashback_bp FROM core.store WHERE merchant = :m").param("m", merchant)
                .query(Integer.class).optional().orElse(DEFAULT_CASHBACK_BP);
        long bubbles = chargedCents * bp / 10_000;
        if (bubbles <= 0) {
            return 0;
        }
        jdbc.sql("""
                        INSERT INTO core.bubble_entry (owner_id, bubbles, reason, ref, description, created_at)
                        VALUES (:owner, :bubbles, 'CASHBACK', :ref, :description, :now)
                        ON CONFLICT (reason, ref) DO NOTHING""")
                .param("owner", ownerId).param("bubbles", bubbles).param("ref", purchaseId)
                .param("description", "Cashback " + storeName).param("now", now()).update();
        return bubbles;
    }

    public Wallet wallet(UUID ownerId) {
        long bubbles = balance(ownerId);
        List<Entry> entries = jdbc.sql("""
                        SELECT bubbles, reason, description, created_at FROM core.bubble_entry
                         WHERE owner_id = :owner ORDER BY id DESC LIMIT 30""")
                .param("owner", ownerId)
                .query((rs, n) -> new Entry(rs.getLong("bubbles"), rs.getString("reason"), rs.getString("description"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        return new Wallet(bubbles, bubbles, entries);
    }

    public List<Store> stores(UUID ownerId, Map<String, String[]> names) {
        return jdbc.sql("""
                        SELECT s.*, c.id AS coupon_id, c.expires_at
                          FROM core.store s
                          LEFT JOIN core.coupon c ON c.merchant = s.merchant AND c.owner_id = :owner
                                                 AND c.used_at IS NULL AND c.expires_at > :now
                         ORDER BY s.cashback_bp DESC""")
                .param("owner", ownerId).param("now", now())
                .query((rs, n) -> {
                    String merchant = rs.getString("merchant");
                    String[] name = names.getOrDefault(merchant, new String[] {merchant, ""});
                    UUID coupon = rs.getObject("coupon_id", UUID.class);
                    return new Store(merchant, name[0], name[1], rs.getString("blurb"), rs.getInt("cashback_bp") / 100.0,
                            rs.getInt("coupon_percent"), rs.getLong("coupon_cap_cents"), rs.getLong("coupon_min_cents"),
                            coupon == null ? null
                                    : new Coupon(coupon, rs.getObject("expires_at", OffsetDateTime.class).toInstant()));
                })
                .list();
    }

    /** Pega o cupom da loja. Cupom vencido sem uso é renovado; cupom ainda válido não duplica. */
    @Transactional
    public Coupon claim(UUID ownerId, String merchant) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM core.store WHERE merchant = :m)").param("m", merchant)
                .query(Boolean.class).single();
        if (!exists) {
            throw ApiException.notFound("store.not_found", "Loja não encontrada.");
        }
        OffsetDateTime now = now();
        OffsetDateTime expires = now.plus(COUPON_LIFE);
        Optional<Coupon> renewed = jdbc.sql("""
                        UPDATE core.coupon SET claimed_at = :now, expires_at = :expires
                         WHERE owner_id = :owner AND merchant = :m AND used_at IS NULL AND expires_at <= :now
                        RETURNING id, expires_at""")
                .param("now", now).param("expires", expires).param("owner", ownerId).param("m", merchant)
                .query((rs, n) -> new Coupon(rs.getObject("id", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional();
        if (renewed.isPresent()) {
            return renewed.get();
        }
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                        INSERT INTO core.coupon (id, owner_id, merchant, claimed_at, expires_at)
                        VALUES (:id, :owner, :m, :now, :expires)
                        ON CONFLICT (owner_id, merchant) WHERE used_at IS NULL DO NOTHING""")
                .param("id", id).param("owner", ownerId).param("m", merchant).param("now", now)
                .param("expires", expires).update();
        if (inserted == 0) {
            throw ApiException.conflict("coupon.active", "Você já tem um cupom desta loja. Use antes de pegar outro.");
        }
        return new Coupon(id, expires.toInstant());
    }

    /**
     * Troca bolhas por saldo. A conta corrente fica travada: duas trocas ao mesmo tempo não gastam as
     * mesmas bolhas, nem uma compra no meio muda o saldo de bolhas lido.
     */
    @Transactional
    public Wallet redeem(UUID ownerId, long bubbles, String idempotencyKey) {
        if (bubbles < MIN_REDEEM) {
            throw ApiException.unprocessable("bubbles.too_few", "Troque a partir de 100 bolhas (R$ 1,00).");
        }
        UUID checking = accounts.checkingId(ownerId);
        ledger.lock(List.of(checking, REWARDS_ACCOUNT));
        UUID ref = UUID.nameUUIDFromBytes(("redeem:" + ownerId + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
        Optional<Long> previous = jdbc.sql("SELECT -bubbles FROM core.bubble_entry WHERE reason = 'REDEEM' AND ref = :ref")
                .param("ref", ref).query(Long.class).optional();
        if (previous.isPresent()) {
            if (previous.get() != bubbles) {
                throw ApiException.conflict("idempotency.mismatch", "Esta Idempotency-Key já foi usada em outra troca.");
            }
            return wallet(ownerId);
        }
        if (balance(ownerId) < bubbles) {
            throw ApiException.unprocessable("bubbles.not_enough", "Você não tem bolhas suficientes.");
        }
        ledger.post(Kind.REWARD, "Bolhas trocadas por saldo", null, List.of(
                Posting.debit(REWARDS_ACCOUNT, bubbles), Posting.credit(checking, bubbles)));
        jdbc.sql("""
                        INSERT INTO core.bubble_entry (owner_id, bubbles, reason, ref, description, created_at)
                        VALUES (:owner, :bubbles, 'REDEEM', :ref, 'Trocadas por saldo', :now)""")
                .param("owner", ownerId).param("bubbles", -bubbles).param("ref", ref).param("now", now()).update();
        return wallet(ownerId);
    }

    private long balance(UUID ownerId) {
        return jdbc.sql("SELECT coalesce(sum(bubbles), 0) FROM core.bubble_entry WHERE owner_id = :owner")
                .param("owner", ownerId).query(Long.class).single();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
