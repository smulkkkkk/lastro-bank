package dev.lastro.core.bill;

import dev.lastro.core.account.AccountService;
import dev.lastro.core.ledger.LedgerService;
import dev.lastro.core.ledger.LedgerService.Kind;
import dev.lastro.core.ledger.Posting;
import dev.lastro.core.shared.ApiException;
import dev.lastro.core.shared.RateLimiter;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pagamento de boleto e recarga de celular. Os dois saem da conta corrente para a conta de lojistas. */
@Service
public class BillService {

    static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    /** Código de banco dos boletos de teste. Fictício, como o resto do dinheiro daqui. */
    static final String TEST_BANK = "777";

    public record BoletoInfo(String line, String bank, String beneficiary, long amountCents, LocalDate dueDate,
            boolean paid) {
    }

    public record Payment(UUID id, String kind, String description, long amountCents, Instant createdAt) {
    }

    /**
     * Comprovante. A autenticação é um resumo SHA-256 do pagamento e do lançamento no razão: o mesmo
     * pagamento sempre mostra o mesmo código, e qualquer mudança nos dados mudaria o código.
     */
    public record Receipt(UUID id, String kind, String description, long amountCents, Instant createdAt,
            String payerName, String payerAccount, String recipient, String line, LocalDate dueDate, String phone,
            String authentication) {
    }

    /** Operadoras fictícias, com os valores que cada uma aceita. */
    public enum Operator {
        ONDA("Onda Móvel"), FAROL("Farol Celular"), CORRENTE("Corrente Telecom");

        static final List<Long> AMOUNTS = List.of(15_00L, 20_00L, 30_00L, 50_00L, 100_00L);
        final String title;

        Operator(String title) {
            this.title = title;
        }
    }

    public record OperatorInfo(Operator code, String name, List<Long> amountsCents) {
    }

    private static final List<String> BENEFICIARIES = List.of("Água Clara Saneamento", "Luz do Vale Energia",
            "Escola Maré Alta", "Condomínio Recife dos Corais", "Internet Correnteza", "Academia Cardume");

    private static final RowMapper<Payment> PAYMENT = (rs, n) -> new Payment(rs.getObject("id", UUID.class),
            rs.getString("kind"), rs.getString("description"), rs.getLong("amount_cents"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final AccountService accounts;
    private final RateLimiter limiter;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    BillService(JdbcClient jdbc, LedgerService ledger, AccountService accounts, RateLimiter limiter, Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.accounts = accounts;
        this.limiter = limiter;
        this.clock = clock;
    }

    public BoletoInfo lookup(String line) {
        Boleto boleto = Boleto.parse(line);
        String beneficiary = registered(boleto).orElseThrow(BillService::unknown);
        boolean paid = jdbc.sql("SELECT EXISTS (SELECT 1 FROM core.bill_payment WHERE barcode = :b)")
                .param("b", boleto.barcode()).query(Boolean.class).single();
        return new BoletoInfo(boleto.line(), boleto.bank(), beneficiary, boleto.amountCents(), boleto.dueDate(), paid);
    }

    /**
     * A linha do boleto travada antes da conta: dois clientes pagando o mesmo boleto ao mesmo tempo
     * esperam um pelo outro, e o segundo recebe "já foi pago".
     */
    @Transactional
    public Payment payBoleto(UUID ownerId, String line, String idempotencyKey) {
        Boleto boleto = Boleto.parse(line);
        UUID checking = accounts.checkingId(ownerId);
        String beneficiary = jdbc.sql("SELECT beneficiary FROM core.boleto WHERE barcode = :b FOR UPDATE")
                .param("b", boleto.barcode()).query(String.class).optional().orElseThrow(BillService::unknown);
        ledger.lock(List.of(checking, LedgerService.MERCHANTS_ACCOUNT));
        Optional<Payment> previous = repeated(ownerId, idempotencyKey, "BOLETO:" + boleto.barcode());
        if (previous.isPresent()) {
            return previous.get();
        }
        if (boleto.dueDate().isBefore(today())) {
            throw ApiException.unprocessable("boleto.overdue", "Boleto vencido. Peça um novo ao emissor, com juros e multa calculados.");
        }
        boolean paid = jdbc.sql("SELECT EXISTS (SELECT 1 FROM core.bill_payment WHERE barcode = :b)")
                .param("b", boleto.barcode()).query(Boolean.class).single();
        if (paid) {
            throw ApiException.conflict("boleto.paid", "Este boleto já foi pago.");
        }
        String description = "Boleto " + beneficiary;
        UUID transactionId = ledger.post(Kind.BILL_PAYMENT, description, null, List.of(
                Posting.debit(checking, boleto.amountCents()), Posting.credit(LedgerService.MERCHANTS_ACCOUNT, boleto.amountCents())));
        return save(ownerId, "BOLETO", boleto.barcode(), null, null, boleto.amountCents(), description,
                "BOLETO:" + boleto.barcode(), idempotencyKey, transactionId);
    }

    @Transactional
    public Payment topUp(UUID ownerId, Operator operator, String phone, long amountCents, String idempotencyKey) {
        if (!Operator.AMOUNTS.contains(amountCents)) {
            throw ApiException.unprocessable("top_up.amount", "Escolha um dos valores da operadora.");
        }
        UUID checking = accounts.checkingId(ownerId);
        ledger.lock(List.of(checking, LedgerService.MERCHANTS_ACCOUNT));
        String request = "TOP_UP:" + operator + ":" + phone + ":" + amountCents;
        Optional<Payment> previous = repeated(ownerId, idempotencyKey, request);
        if (previous.isPresent()) {
            return previous.get();
        }
        String description = "Recarga " + operator.title + " " + masked(phone);
        UUID transactionId = ledger.post(Kind.TOP_UP, description, null, List.of(
                Posting.debit(checking, amountCents), Posting.credit(LedgerService.MERCHANTS_ACCOUNT, amountCents)));
        return save(ownerId, "TOP_UP", null, operator.name(), phone, amountCents, description, request,
                idempotencyKey, transactionId);
    }

    /** Gera um boleto de teste registrado, para experimentar o pagamento. */
    public BoletoInfo sample(UUID ownerId) {
        limiter.check("boleto-sample:" + ownerId, 10);
        long amount = (30 + random.nextInt(450)) * 100L + random.nextInt(100);
        LocalDate due = today().plusDays(5 + random.nextInt(16));
        StringBuilder free = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            free.append(random.nextInt(10));
        }
        Boleto boleto = Boleto.create(TEST_BANK, amount, due, free.toString());
        String beneficiary = BENEFICIARIES.get(random.nextInt(BENEFICIARIES.size()));
        jdbc.sql("""
                        INSERT INTO core.boleto (barcode, beneficiary, amount_cents, due_date, issued_for, created_at)
                        VALUES (:b, :name, :cents, :due, :owner, :now)""")
                .param("b", boleto.barcode()).param("name", beneficiary).param("cents", amount).param("due", due)
                .param("owner", ownerId).param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
        return new BoletoInfo(boleto.line(), boleto.bank(), beneficiary, amount, due, false);
    }

    /** Só o dono vê o comprovante; para os outros, o pagamento não existe. */
    public Receipt receipt(UUID ownerId, UUID id) {
        return jdbc.sql("""
                        SELECT p.*, u.full_name, a.branch, a.number, b.beneficiary, b.due_date
                          FROM core.bill_payment p
                          JOIN core.app_user u ON u.id = p.owner_id
                          JOIN core.account a ON a.owner_id = p.owner_id AND a.kind = 'CHECKING'
                          LEFT JOIN core.boleto b ON b.barcode = p.barcode
                         WHERE p.id = :id AND p.owner_id = :owner""")
                .param("id", id).param("owner", ownerId)
                .query((rs, n) -> {
                    String kind = rs.getString("kind");
                    String barcode = rs.getString("barcode");
                    String operator = rs.getString("operator");
                    Instant at = rs.getObject("created_at", OffsetDateTime.class).toInstant();
                    UUID transaction = rs.getObject("transaction_id", UUID.class);
                    long cents = rs.getLong("amount_cents");
                    return new Receipt(id, kind, rs.getString("description"), cents, at, rs.getString("full_name"),
                            "Agência " + rs.getString("branch") + ", conta " + rs.getString("number"),
                            barcode != null ? rs.getString("beneficiary") : Operator.valueOf(operator).title,
                            barcode != null ? Boleto.parse(barcode).line() : null,
                            barcode != null ? rs.getObject("due_date", LocalDate.class) : null,
                            barcode == null ? masked(rs.getString("phone")) : null,
                            authentication(id, transaction, cents, at));
                })
                .optional()
                .orElseThrow(() -> ApiException.notFound("bill.not_found", "Pagamento não encontrado."));
    }

    static String authentication(UUID id, UUID transaction, long cents, Instant at) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                    (id + "|" + transaction + "|" + cents + "|" + at).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String hex = java.util.HexFormat.of().withUpperCase().formatHex(digest).substring(0, 32);
            return String.join(".", hex.split("(?<=\\G.{4})"));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<OperatorInfo> operators() {
        return Arrays.stream(Operator.values()).map(o -> new OperatorInfo(o, o.title, Operator.AMOUNTS)).toList();
    }

    public List<Payment> recent(UUID ownerId) {
        return jdbc.sql("SELECT * FROM core.bill_payment WHERE owner_id = :owner ORDER BY created_at DESC, id LIMIT 20")
                .param("owner", ownerId).query(PAYMENT).list();
    }

    private Optional<String> registered(Boleto boleto) {
        return jdbc.sql("SELECT beneficiary FROM core.boleto WHERE barcode = :b").param("b", boleto.barcode())
                .query(String.class).optional();
    }

    private Optional<Payment> repeated(UUID ownerId, String key, String request) {
        Optional<String> stored = jdbc.sql("SELECT request FROM core.bill_payment WHERE owner_id = :owner AND idempotency_key = :key")
                .param("owner", ownerId).param("key", key).query(String.class).optional();
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        if (!stored.get().equals(request)) {
            throw ApiException.conflict("idempotency.mismatch", "Esta Idempotency-Key já foi usada em outro pagamento.");
        }
        return jdbc.sql("SELECT * FROM core.bill_payment WHERE owner_id = :owner AND idempotency_key = :key")
                .param("owner", ownerId).param("key", key).query(PAYMENT).optional();
    }

    private Payment save(UUID ownerId, String kind, String barcode, String operator, String phone, long cents,
            String description, String request, String key, UUID transactionId) {
        try {
            return jdbc.sql("""
                            INSERT INTO core.bill_payment (id, owner_id, kind, barcode, operator, phone, amount_cents,
                                                           description, request, idempotency_key, transaction_id, created_at)
                            VALUES (:id, :owner, :kind, :barcode, :operator, :phone, :cents, :description, :request,
                                    :key, :tx, :at)
                            RETURNING *""")
                    .param("id", UUID.randomUUID()).param("owner", ownerId).param("kind", kind).param("barcode", barcode)
                    .param("operator", operator).param("phone", phone).param("cents", cents)
                    .param("description", description).param("request", request).param("key", key)
                    .param("tx", transactionId).param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                    .query(PAYMENT).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("boleto.paid", "Este boleto já foi pago.");
        }
    }

    /** (11) •••••-4321: o extrato não precisa do número inteiro. */
    static String masked(String phone) {
        return "(" + phone.substring(0, 2) + ") •••••-" + phone.substring(7);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), SAO_PAULO);
    }

    private static ApiException unknown() {
        return ApiException.notFound("boleto.not_found", "Boleto não encontrado no registro. Confira com quem emitiu.");
    }
}
