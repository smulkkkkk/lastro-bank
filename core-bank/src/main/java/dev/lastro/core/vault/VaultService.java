package dev.lastro.core.vault;

import dev.lastro.core.account.AccountService;
import dev.lastro.core.ledger.LedgerService;
import dev.lastro.core.ledger.LedgerService.Kind;
import dev.lastro.core.ledger.Posting;
import dev.lastro.core.shared.ApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class VaultService {

    static final int FREE_PERCENT = 100;
    static final int LOCKED_PERCENT = 110;
    private static final int MIN_LOCK_DAYS = 30;
    private static final int MAX_LOCK_YEARS = 5;
    private static final int MAX_VAULTS = 10;
    private static final int BUSINESS_DAYS = 252;

    record Vault(UUID id, String name, Long goalCents, int cdiPercent, LocalDate lockedUntil, boolean locked,
            long balanceCents, long yieldCents) {
    }

    record Cdi(LocalDate day, BigDecimal dailyPercent, BigDecimal yearPercent) {
    }

    record Overview(Cdi cdi, long totalCents, List<Vault> items) {
    }

    private static final String SELECT = """
            SELECT v.account_id, v.name, v.goal_cents, v.cdi_percent, v.locked_until, a.balance_cents,
                   (SELECT coalesce(sum(y.posted_cents), 0) FROM core.vault_yield y
                     WHERE y.vault_id = v.account_id) AS yield_cents
              FROM core.vault v JOIN core.account a ON a.id = v.account_id
             WHERE v.owner_id = :owner AND v.archived_on IS NULL\s""";

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final AccountService accounts;
    private final YieldService yields;

    VaultService(JdbcClient jdbc, LedgerService ledger, AccountService accounts, YieldService yields) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.accounts = accounts;
        this.yields = yields;
    }

    Overview overview(UUID ownerId) {
        List<Vault> items = jdbc.sql(SELECT + "ORDER BY a.created_at, v.account_id")
                .param("owner", ownerId).query(mapper()).list();
        Cdi cdi = jdbc.sql("SELECT day, daily_percent FROM core.cdi_rate ORDER BY day DESC LIMIT 1")
                .query((rs, n) -> new Cdi(rs.getObject("day", LocalDate.class),
                        rs.getBigDecimal("daily_percent").stripTrailingZeros(),
                        yearPercent(rs.getBigDecimal("daily_percent"))))
                .optional().orElse(null);
        return new Overview(cdi, items.stream().mapToLong(Vault::balanceCents).sum(), items);
    }

    @Transactional
    public Vault create(UUID ownerId, String name, Long goalCents, LocalDate lockedUntil) {
        LocalDate today = yields.today();
        if (lockedUntil != null && (lockedUntil.isBefore(today.plusDays(MIN_LOCK_DAYS))
                || lockedUntil.isAfter(today.plusYears(MAX_LOCK_YEARS)))) {
            throw ApiException.unprocessable("vault.lock_date", "A trava vai de 30 dias a 5 anos.");
        }
        // Trava a conta para dois pedidos juntos não passarem do máximo.
        ledger.lock(List.of(accounts.checkingId(ownerId)));
        long open = jdbc.sql("SELECT count(*) FROM core.vault WHERE owner_id = :owner AND archived_on IS NULL")
                .param("owner", ownerId).query(Long.class).single();
        if (open >= MAX_VAULTS) {
            throw ApiException.unprocessable("vault.too_many", "Você já tem 10 caixinhas.");
        }
        UUID id = accounts.openVault(ownerId);
        jdbc.sql("""
                        INSERT INTO core.vault (account_id, owner_id, name, goal_cents, cdi_percent, locked_until, created_on)
                        VALUES (:id, :owner, :name, :goal, :percent, :locked, :today)""")
                .param("id", id).param("owner", ownerId).param("name", name.strip()).param("goal", goalCents)
                .param("percent", lockedUntil == null ? FREE_PERCENT : LOCKED_PERCENT)
                .param("locked", lockedUntil).param("today", today)
                .update();
        return find(id, ownerId);
    }

    @Transactional
    public Vault deposit(UUID id, UUID ownerId, long cents, String idempotencyKey) {
        return move(id, ownerId, cents, "in", idempotencyKey);
    }

    @Transactional
    public Vault withdraw(UUID id, UUID ownerId, long cents, String idempotencyKey) {
        return move(id, ownerId, cents, "out", idempotencyKey);
    }

    @Transactional
    public void archive(UUID id, UUID ownerId) {
        find(id, ownerId);
        ledger.lock(List.of(id));
        if (find(id, ownerId).balanceCents() > 0) {
            throw ApiException.unprocessable("vault.not_empty", "Resgate todo o dinheiro antes de excluir.");
        }
        jdbc.sql("UPDATE core.vault SET archived_on = :today WHERE account_id = :id")
                .param("today", yields.today()).param("id", id).update();
    }

    private Vault move(UUID id, UUID ownerId, long cents, String way, String idempotencyKey) {
        find(id, ownerId);
        UUID checking = accounts.checkingId(ownerId);
        ledger.lock(List.of(id, checking));
        // Lê de novo depois da trava: a caixinha pode ter sido excluída enquanto esperava.
        Vault vault = find(id, ownerId);

        String ref = "vault:" + id + ":" + way + ":" + idempotencyKey;
        Optional<Long> previous = jdbc.sql("""
                        SELECT e.amount_cents FROM core.ledger_transaction t
                          JOIN core.ledger_entry e ON e.transaction_id = t.id AND e.account_id = :id
                         WHERE t.external_ref = :ref""")
                .param("id", id).param("ref", ref).query(Long.class).optional();
        if (previous.isPresent()) {
            if (previous.get() != cents) {
                throw ApiException.conflict("idempotency.mismatch",
                        "Esta Idempotency-Key já foi usada com outro valor.");
            }
            return vault;
        }
        if (way.equals("in")) {
            ledger.post(Kind.VAULT_IN, "Guardado em " + vault.name(), ref, List.of(
                    Posting.debit(checking, cents), Posting.credit(id, cents)));
        } else {
            if (vault.locked()) {
                throw ApiException.unprocessable("vault.locked", "Esta caixinha está trancada até a data escolhida.");
            }
            ledger.post(Kind.VAULT_OUT, "Resgatado de " + vault.name(), ref, List.of(
                    Posting.debit(id, cents), Posting.credit(checking, cents)));
        }
        return find(id, ownerId);
    }

    private Vault find(UUID id, UUID ownerId) {
        return jdbc.sql(SELECT + "AND v.account_id = :id")
                .param("owner", ownerId).param("id", id).query(mapper()).optional()
                .orElseThrow(() -> ApiException.notFound("vault.not_found", "Caixinha não encontrada."));
    }

    private RowMapper<Vault> mapper() {
        LocalDate today = yields.today();
        return (rs, n) -> {
            LocalDate lockedUntil = rs.getObject("locked_until", LocalDate.class);
            return new Vault(rs.getObject("account_id", UUID.class), rs.getString("name"),
                    rs.getObject("goal_cents", Long.class), rs.getInt("cdi_percent"), lockedUntil,
                    lockedUntil != null && lockedUntil.isAfter(today),
                    rs.getLong("balance_cents"), rs.getLong("yield_cents"));
        };
    }

    /** Taxa anual equivalente, com 252 dias úteis. Só para mostrar: nenhum rendimento usa este número. */
    static BigDecimal yearPercent(BigDecimal dailyPercent) {
        BigDecimal factor = BigDecimal.ONE.add(dailyPercent.movePointLeft(2)).pow(BUSINESS_DAYS, MathContext.DECIMAL64);
        return factor.subtract(BigDecimal.ONE).movePointRight(2).setScale(2, RoundingMode.HALF_EVEN);
    }
}
