package dev.lastro.core.openfinance;

import dev.lastro.core.account.AccountService;
import dev.lastro.core.ledger.LedgerService;
import dev.lastro.core.ledger.LedgerService.Kind;
import dev.lastro.core.ledger.Posting;
import dev.lastro.core.shared.ApiException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Open Finance simulado. O fluxo segue o de verdade: o cliente pede o compartilhamento aqui, autoriza
 * no outro banco, os dados só aparecem enquanto o consentimento vale, e revogar corta tudo na hora.
 * Os bancos são fictícios; o saldo e o extrato de lá são gerados.
 */
@Service
public class OpenFinanceService {

    public enum Permission { BALANCES, TRANSACTIONS, PAYMENTS }

    public record Bank(String code, String name) {
    }

    public record ExternalAccount(String branch, String number, Long balanceCents) {
    }

    /** status: AWAITING, AUTHORIZED, REJECTED, REVOKED ou EXPIRED (autorizado e vencido). */
    public record Consent(UUID id, String bankCode, String bankName, List<Permission> permissions, String status,
            int months, Instant createdAt, Instant expiresAt, ExternalAccount account) {
    }

    public record ExternalTransaction(Instant at, String description, long amountCents) {
    }

    public record Transfer(UUID id, long amountCents, long remainingCents, Instant createdAt) {
    }

    static final long MAX_TRANSFER_CENTS = 5_000_00;
    private static final List<String> SPENDING = List.of("Supermercado Bom Porto", "Farmácia Estrela-do-Mar",
            "Restaurante Ostra", "Transporte por aplicativo", "Streaming de música", "Conta de luz", "Padaria do Cais");
    private static final List<String> INCOME = List.of("Salário", "Pix recebido", "Reembolso");

    private record Row(UUID id, UUID owner, String bankCode, String bankName, List<Permission> permissions,
            String status, int months, Instant createdAt, Instant expiresAt, String branch, String number,
            Long balance, Long seed) {
    }

    private final RowMapper<Row> rowMapper = (rs, n) -> {
        String[] perms = (String[]) rs.getArray("permissions").getArray();
        OffsetDateTime expires = rs.getObject("expires_at", OffsetDateTime.class);
        long balance = rs.getLong("balance_cents");
        boolean hasAccount = !rs.wasNull();
        long seed = rs.getLong("seed");
        return new Row(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class), rs.getString("bank_code"),
                rs.getString("name"), java.util.Arrays.stream(perms).map(Permission::valueOf).toList(),
                rs.getString("status"), rs.getInt("months"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                expires == null ? null : expires.toInstant(), rs.getString("branch"), rs.getString("number"),
                hasAccount ? balance : null, hasAccount ? seed : null);
    };

    private static final String SELECT = """
            SELECT c.*, b.name, x.branch, x.number, x.balance_cents, x.seed
              FROM core.consent c
              JOIN core.external_bank b ON b.code = c.bank_code
              LEFT JOIN core.external_account x ON x.consent_id = c.id
            """;

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final AccountService accounts;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    OpenFinanceService(JdbcClient jdbc, LedgerService ledger, AccountService accounts, Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.accounts = accounts;
        this.clock = clock;
    }

    public List<Bank> banks() {
        return jdbc.sql("SELECT code, name FROM core.external_bank ORDER BY name")
                .query((rs, n) -> new Bank(rs.getString("code"), rs.getString("name"))).list();
    }

    public List<Consent> consents(UUID ownerId) {
        return jdbc.sql(SELECT + " WHERE c.owner_id = :owner ORDER BY c.created_at DESC").param("owner", ownerId)
                .query(rowMapper).list().stream().map(this::view).toList();
    }

    /** Pede o compartilhamento. Fica aguardando até o cliente autorizar no outro banco. */
    @Transactional
    public Consent request(UUID ownerId, String bankCode, Set<Permission> permissions, int months) {
        if (months != 3 && months != 6 && months != 12) {
            throw ApiException.badRequest("consent.months", "Escolha 3, 6 ou 12 meses.");
        }
        if (permissions.isEmpty()) {
            throw ApiException.badRequest("consent.permissions", "Escolha pelo menos um dado para compartilhar.");
        }
        boolean bank = jdbc.sql("SELECT EXISTS (SELECT 1 FROM core.external_bank WHERE code = :c)").param("c", bankCode)
                .query(Boolean.class).single();
        if (!bank) {
            throw ApiException.notFound("bank.not_found", "Banco não encontrado.");
        }
        OffsetDateTime now = now();
        // Consentimento vencido deixa de ser vivo: libera pedir de novo.
        jdbc.sql("""
                        UPDATE core.consent SET status = 'REVOKED', ended_at = expires_at
                         WHERE owner_id = :owner AND bank_code = :bank AND status = 'AUTHORIZED' AND expires_at <= :now""")
                .param("owner", ownerId).param("bank", bankCode).param("now", now).update();
        UUID id = UUID.randomUUID();
        String[] perms = permissions.stream().sorted().map(Enum::name).toArray(String[]::new);
        int inserted = jdbc.sql("""
                        INSERT INTO core.consent (id, owner_id, bank_code, permissions, status, months, created_at)
                        VALUES (:id, :owner, :bank, :perms, 'AWAITING', :months, :now)
                        ON CONFLICT (owner_id, bank_code) WHERE status IN ('AWAITING', 'AUTHORIZED') DO NOTHING""")
                .param("id", id).param("owner", ownerId).param("bank", bankCode).param("perms", perms)
                .param("months", months).param("now", now).update();
        if (inserted == 0) {
            throw ApiException.conflict("consent.live", "Você já tem um compartilhamento com este banco. Revogue antes de pedir outro.");
        }
        return get(ownerId, id);
    }

    /** O que aconteceria na tela do outro banco: o cliente aprova ou recusa. */
    @Transactional
    public Consent authorize(UUID ownerId, UUID id, boolean approve) {
        Row row = locked(ownerId, id);
        if (!row.status().equals("AWAITING")) {
            throw ApiException.conflict("consent.not_awaiting", "Este pedido não está mais aguardando autorização.");
        }
        OffsetDateTime now = now();
        if (!approve) {
            jdbc.sql("UPDATE core.consent SET status = 'REJECTED', ended_at = :now WHERE id = :id")
                    .param("now", now).param("id", id).update();
            return get(ownerId, id);
        }
        jdbc.sql("UPDATE core.consent SET status = 'AUTHORIZED', authorized_at = :now, expires_at = :expires WHERE id = :id")
                .param("now", now).param("expires", now.plusMonths(row.months())).param("id", id).update();
        jdbc.sql("""
                        INSERT INTO core.external_account (consent_id, branch, number, balance_cents, seed)
                        VALUES (:id, :branch, :number, :balance, :seed)""")
                .param("id", id).param("branch", String.format("%04d", 1 + random.nextInt(9998)))
                .param("number", (100_000 + random.nextInt(899_999)) + "-" + random.nextInt(10))
                .param("balance", (800 + random.nextInt(5200)) * 100L + random.nextInt(100))
                .param("seed", random.nextLong()).update();
        return get(ownerId, id);
    }

    /** Revogar corta o acesso na hora. */
    @Transactional
    public Consent revoke(UUID ownerId, UUID id) {
        Row row = locked(ownerId, id);
        if (!row.status().equals("AWAITING") && !row.status().equals("AUTHORIZED")) {
            throw ApiException.conflict("consent.ended", "Este compartilhamento já terminou.");
        }
        jdbc.sql("UPDATE core.consent SET status = 'REVOKED', ended_at = :now WHERE id = :id")
                .param("now", now()).param("id", id).update();
        return get(ownerId, id);
    }

    public List<ExternalTransaction> transactions(UUID ownerId, UUID id) {
        Row row = active(ownerId, id, Permission.TRANSACTIONS);
        Random generator = new Random(row.seed());
        Instant now = clock.instant();
        List<ExternalTransaction> items = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Instant at = now.minus(1 + generator.nextInt(30), ChronoUnit.DAYS).minus(generator.nextInt(600), ChronoUnit.MINUTES);
            boolean income = generator.nextInt(5) == 0;
            String description = income ? INCOME.get(generator.nextInt(INCOME.size())) : SPENDING.get(generator.nextInt(SPENDING.size()));
            long cents = income ? (200 + generator.nextInt(2800)) * 100L : (8 + generator.nextInt(240)) * 100L + generator.nextInt(100);
            items.add(new ExternalTransaction(at, description, income ? cents : -cents));
        }
        jdbc.sql("SELECT amount_cents, created_at FROM core.external_transfer WHERE consent_id = :id").param("id", id)
                .query((rs, n) -> new ExternalTransaction(rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        "Enviado para o Lastro", -rs.getLong("amount_cents")))
                .list().forEach(items::add);
        items.sort(Comparator.comparing(ExternalTransaction::at).reversed());
        return items;
    }

    /**
     * Traz dinheiro do outro banco (iniciação de pagamento). A conta de lá fica travada: duas
     * transferências ao mesmo tempo não tiram mais do que existe.
     */
    @Transactional
    public Transfer bring(UUID ownerId, UUID id, long cents, String idempotencyKey) {
        if (cents <= 0 || cents > MAX_TRANSFER_CENTS) {
            throw ApiException.unprocessable("transfer.amount", "Traga de R$ 0,01 até R$ 5.000,00 por vez.");
        }
        Row row = active(ownerId, id, Permission.PAYMENTS);
        long balance = jdbc.sql("SELECT balance_cents FROM core.external_account WHERE consent_id = :id FOR UPDATE")
                .param("id", id).query(Long.class).single();
        Optional<Transfer> previous = jdbc.sql("""
                        SELECT id, amount_cents, created_at FROM core.external_transfer
                         WHERE consent_id = :id AND idempotency_key = :key""")
                .param("id", id).param("key", idempotencyKey)
                .query((rs, n) -> new Transfer(rs.getObject("id", UUID.class), rs.getLong("amount_cents"), balance,
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
        if (previous.isPresent()) {
            if (previous.get().amountCents() != cents) {
                throw ApiException.conflict("idempotency.mismatch", "Esta Idempotency-Key já foi usada em outra transferência.");
            }
            return previous.get();
        }
        if (cents > balance) {
            throw ApiException.unprocessable("transfer.insufficient", "Saldo insuficiente no " + row.bankName() + ".");
        }
        UUID checking = accounts.checkingId(ownerId);
        ledger.lock(List.of(checking, LedgerService.FUNDING_ACCOUNT));
        UUID transactionId = ledger.post(Kind.OPEN_FINANCE_IN, "Trazido do " + row.bankName(), null, List.of(
                Posting.debit(LedgerService.FUNDING_ACCOUNT, cents), Posting.credit(checking, cents)));
        jdbc.sql("UPDATE core.external_account SET balance_cents = balance_cents - :cents WHERE consent_id = :id")
                .param("cents", cents).param("id", id).update();
        UUID transferId = UUID.randomUUID();
        OffsetDateTime now = now();
        jdbc.sql("""
                        INSERT INTO core.external_transfer (id, consent_id, amount_cents, idempotency_key, transaction_id, created_at)
                        VALUES (:id, :consent, :cents, :key, :tx, :now)""")
                .param("id", transferId).param("consent", id).param("cents", cents).param("key", idempotencyKey)
                .param("tx", transactionId).param("now", now).update();
        return new Transfer(transferId, cents, balance - cents, now.toInstant());
    }

    private Row active(UUID ownerId, UUID id, Permission needed) {
        Row row = jdbc.sql(SELECT + " WHERE c.id = :id AND c.owner_id = :owner").param("id", id).param("owner", ownerId)
                .query(rowMapper).optional().orElseThrow(OpenFinanceService::notFound);
        if (!status(row).equals("AUTHORIZED")) {
            throw ApiException.unprocessable("consent.inactive", "Este compartilhamento não está ativo.");
        }
        if (!row.permissions().contains(needed)) {
            throw ApiException.unprocessable("consent.permission", "Você não autorizou este dado no compartilhamento.");
        }
        return row;
    }

    private Row locked(UUID ownerId, UUID id) {
        return jdbc.sql(SELECT + " WHERE c.id = :id AND c.owner_id = :owner FOR UPDATE OF c").param("id", id)
                .param("owner", ownerId).query(rowMapper).optional().orElseThrow(OpenFinanceService::notFound);
    }

    private Consent get(UUID ownerId, UUID id) {
        return view(jdbc.sql(SELECT + " WHERE c.id = :id AND c.owner_id = :owner").param("id", id)
                .param("owner", ownerId).query(rowMapper).single());
    }

    private String status(Row row) {
        return row.status().equals("AUTHORIZED") && !row.expiresAt().isAfter(clock.instant()) ? "EXPIRED" : row.status();
    }

    /** Fora de um consentimento ativo, nada da conta de lá aparece. */
    private Consent view(Row row) {
        String status = status(row);
        ExternalAccount account = status.equals("AUTHORIZED") && row.branch() != null
                ? new ExternalAccount(row.branch(), row.number(),
                        row.permissions().contains(Permission.BALANCES) ? row.balance() : null)
                : null;
        return new Consent(row.id(), row.bankCode(), row.bankName(), row.permissions(), status, row.months(),
                row.createdAt(), row.expiresAt(), account);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static ApiException notFound() {
        return ApiException.notFound("consent.not_found", "Compartilhamento não encontrado.");
    }
}
