package dev.lastro.core.vault;

import dev.lastro.core.ledger.LedgerService;
import dev.lastro.core.ledger.LedgerService.Kind;
import dev.lastro.core.ledger.Posting;
import dev.lastro.core.vault.CdiSource.Rate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rende um dia útil de cada vez. Pode rodar quantas vezes quiser, em quantas instâncias quiser: o
 * mesmo dia da mesma caixinha só é pago uma vez, e dias perdidos são pagos quando a taxa chegar.
 */
@Service
class YieldService {

    static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final Logger log = LoggerFactory.getLogger(YieldService.class);
    private static final int FIRST_FETCH_DAYS = 15;
    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);

    private record Pending(UUID vaultId, LocalDate day, BigDecimal dailyPercent, int cdiPercent) {
    }

    private final JdbcClient jdbc;
    private final CdiSource source;
    private final LedgerService ledger;
    private final TransactionTemplate transaction;
    private final Clock clock;

    YieldService(JdbcClient jdbc, CdiSource source, LedgerService ledger, TransactionTemplate transaction, Clock clock) {
        this.jdbc = jdbc;
        this.source = source;
        this.ledger = ledger;
        this.transaction = transaction;
        this.clock = clock;
    }

    void run() {
        try {
            fetchRates();
        } catch (RuntimeException e) {
            // Sem taxa nova ninguém rende a mais nem a menos; os dias são pagos na próxima vez.
            log.warn("Não foi possível buscar o CDI: {}", e.getMessage());
        }
        accrue();
    }

    void fetchRates() {
        LocalDate today = today();
        LocalDate from = jdbc.sql("SELECT max(day) FROM core.cdi_rate")
                .query((rs, n) -> rs.getObject(1, LocalDate.class))
                .list().stream().filter(day -> day != null).findFirst()
                .map(last -> last.plusDays(1))
                .orElse(today.minusDays(FIRST_FETCH_DAYS));
        if (from.isAfter(today)) {
            return;
        }
        for (Rate rate : source.between(from, today)) {
            jdbc.sql("INSERT INTO core.cdi_rate (day, daily_percent) VALUES (:day, :percent) ON CONFLICT DO NOTHING")
                    .param("day", rate.day()).param("percent", rate.dailyPercent()).update();
        }
    }

    /** Só dias encerrados: o saldo que rende é o do fim do dia. */
    void accrue() {
        List<Pending> pending = jdbc.sql("""
                        SELECT v.account_id, r.day, r.daily_percent, v.cdi_percent
                          FROM core.vault v
                          JOIN core.cdi_rate r ON r.day >= v.created_on AND r.day < :today
                                              AND (v.archived_on IS NULL OR r.day < v.archived_on)
                          LEFT JOIN core.vault_yield y ON y.vault_id = v.account_id AND y.day = r.day
                         WHERE y.day IS NULL
                         ORDER BY r.day, v.account_id""")
                .param("today", today())
                .query((rs, n) -> new Pending(rs.getObject("account_id", UUID.class),
                        rs.getObject("day", LocalDate.class), rs.getBigDecimal("daily_percent"),
                        rs.getInt("cdi_percent")))
                .list();
        for (Pending item : pending) {
            try {
                transaction.executeWithoutResult(status -> pay(item));
            } catch (RuntimeException e) {
                log.error("Rendimento não pago: caixinha {} dia {}", item.vaultId(), item.day(), e);
            }
        }
    }

    private void pay(Pending item) {
        // Caixinha encerrada com rendimento a receber: o dinheiro vai para a conta do dono.
        UUID target = jdbc.sql("""
                        SELECT CASE WHEN v.archived_on IS NULL THEN v.account_id ELSE a.id END
                          FROM core.vault v JOIN core.account a ON a.owner_id = v.owner_id AND a.kind = 'CHECKING'
                         WHERE v.account_id = :id""")
                .param("id", item.vaultId()).query(UUID.class).single();
        ledger.lock(List.of(LedgerService.INTEREST_ACCOUNT, item.vaultId(), target));
        boolean paid = jdbc.sql("SELECT EXISTS (SELECT 1 FROM core.vault_yield WHERE vault_id = :id AND day = :day)")
                .param("id", item.vaultId()).param("day", item.day()).query(Boolean.class).single();
        if (paid) {
            return;
        }
        long base = jdbc.sql("""
                        SELECT coalesce(sum(CASE direction WHEN 'CREDIT' THEN amount_cents ELSE -amount_cents END), 0)
                          FROM core.ledger_entry
                         WHERE account_id = :id
                           AND created_at < (CAST(:day AS date) + 1)::timestamp AT TIME ZONE 'America/Sao_Paulo'""")
                .param("id", item.vaultId()).param("day", item.day()).query(Long.class).single();
        BigDecimal remainder = jdbc.sql("SELECT yield_remainder FROM core.vault WHERE account_id = :id")
                .param("id", item.vaultId()).query(BigDecimal.class).single();

        BigDecimal exact = exactYield(base, item.dailyPercent(), item.cdiPercent());
        BigDecimal due = exact.add(remainder);
        long posted = due.setScale(0, RoundingMode.DOWN).longValueExact();

        jdbc.sql("""
                        INSERT INTO core.vault_yield (vault_id, day, base_cents, exact_cents, posted_cents)
                        VALUES (:id, :day, :base, :exact, :posted)""")
                .param("id", item.vaultId()).param("day", item.day())
                .param("base", base).param("exact", exact).param("posted", posted)
                .update();
        jdbc.sql("UPDATE core.vault SET yield_remainder = :rest WHERE account_id = :id")
                .param("rest", due.subtract(BigDecimal.valueOf(posted))).param("id", item.vaultId()).update();
        if (posted > 0) {
            ledger.post(Kind.YIELD, "Rendimento da caixinha", "yield:" + item.vaultId() + ":" + item.day(), List.of(
                    Posting.debit(LedgerService.INTEREST_ACCOUNT, posted),
                    Posting.credit(target, posted)));
        }
    }

    /** Centavos exatos, sem ponto flutuante. Arredonda para baixo na décima casa. */
    static BigDecimal exactYield(long baseCents, BigDecimal dailyPercent, int cdiPercent) {
        return BigDecimal.valueOf(baseCents)
                .multiply(dailyPercent)
                .multiply(BigDecimal.valueOf(cdiPercent))
                .divide(TEN_THOUSAND, 10, RoundingMode.DOWN);
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(SAO_PAULO));
    }
}
