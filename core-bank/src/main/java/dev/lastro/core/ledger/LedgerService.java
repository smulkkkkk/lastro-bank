package dev.lastro.core.ledger;

import dev.lastro.core.ledger.Posting.Direction;
import dev.lastro.core.shared.ApiException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Única porta de entrada para movimentar dinheiro. */
@Service
public class LedgerService {

    public static final UUID FUNDING_ACCOUNT = new UUID(0, 1);
    public static final UUID MERCHANTS_ACCOUNT = new UUID(0, 2);
    public static final UUID INTEREST_ACCOUNT = new UUID(0, 3);
    public static final UUID BROKER_ACCOUNT = new UUID(0, 4);

    public enum Kind { WELCOME, DEPOSIT, TRANSFER, VAULT_IN, VAULT_OUT, YIELD, CARD_PURCHASE, INVOICE_PAYMENT,
        CREDIT_CONVERSION, LOAN, LOAN_PAYMENT,
        INVESTMENT_BUY, INVESTMENT_SELL, REVERSAL }

    /** O que pode sair: saldo livre mais o limite, que só existe em conta de crédito e de empréstimo. */
    public record LockedAccount(UUID id, String kind, long balanceCents, long heldCents, long creditLimitCents) {
        public long availableCents() {
            return balanceCents - heldCents + creditLimitCents;
        }

        boolean system() {
            return kind.equals("SYSTEM");
        }
    }

    private final JdbcClient jdbc;

    LedgerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Trava as contas sempre em ordem de id. Duas operações sobre as mesmas contas esperam uma
     * pela outra, em vez de entrar em deadlock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, LockedAccount> lock(Collection<UUID> accountIds) {
        List<UUID> ids = accountIds.stream().distinct().toList();
        Map<UUID, LockedAccount> locked = jdbc.sql("""
                        SELECT id, kind, balance_cents, held_cents, credit_limit_cents FROM core.account
                         WHERE id IN (:ids) ORDER BY id FOR UPDATE""")
                .param("ids", ids)
                .query((rs, n) -> new LockedAccount(
                        rs.getObject("id", UUID.class),
                        rs.getString("kind"),
                        rs.getLong("balance_cents"),
                        rs.getLong("held_cents"),
                        rs.getLong("credit_limit_cents")))
                .list().stream()
                .collect(Collectors.toMap(LockedAccount::id, Function.identity()));
        if (locked.size() != ids.size()) {
            throw ApiException.notFound("account.not_found", "Conta não encontrada.");
        }
        return locked;
    }

    /** Valor que um pedido já repetido moveu nesta conta, se ele já foi lançado. */
    public Optional<Long> amountByRef(String externalRef, UUID accountId) {
        return jdbc.sql("""
                        SELECT e.amount_cents FROM core.ledger_transaction t
                          JOIN core.ledger_entry e ON e.transaction_id = t.id AND e.account_id = :account
                         WHERE t.external_ref = :ref""")
                .param("account", accountId).param("ref", externalRef)
                .query(Long.class).optional();
    }

    /** Exige transação aberta: quem chama decide o que precisa ser atômico com o lançamento. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(Kind kind, String description, String externalRef, List<Posting> postings) {
        Map<UUID, LockedAccount> accounts = lock(postings.stream().map(Posting::accountId).toList());

        Map<UUID, Long> change = new HashMap<>();
        for (Posting posting : postings) {
            long signed = posting.direction() == Direction.CREDIT ? posting.amountCents() : -posting.amountCents();
            change.merge(posting.accountId(), signed, Math::addExact);
        }
        change.forEach((id, cents) -> {
            LockedAccount account = accounts.get(id);
            if (!account.system() && cents < 0 && account.availableCents() + cents < 0) {
                throw account.kind().equals("CREDIT")
                        ? ApiException.unprocessable("credit.limit", "Limite de crédito insuficiente.")
                        : ApiException.unprocessable("ledger.insufficient_funds", "Saldo insuficiente.");
            }
        });

        UUID transactionId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO core.ledger_transaction (id, kind, description, external_ref)
                        VALUES (:id, :kind, :description, :ref)""")
                .param("id", transactionId)
                .param("kind", kind.name())
                .param("description", description)
                .param("ref", externalRef)
                .update();
        // Créditos primeiro, para um lançamento composto não ficar negativo no meio do caminho.
        postings.stream()
                .sorted((a, b) -> b.direction().compareTo(a.direction()))
                .forEach(posting -> jdbc.sql("""
                                INSERT INTO core.ledger_entry (transaction_id, account_id, direction, amount_cents, description)
                                VALUES (:tx, :account, :direction, :amount, :description)""")
                        .param("tx", transactionId)
                        .param("account", posting.accountId())
                        .param("direction", posting.direction().name())
                        .param("amount", posting.amountCents())
                        .param("description", posting.description())
                        .update());
        return transactionId;
    }
}
