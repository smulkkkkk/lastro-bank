package dev.lastro.core.ledger;

import java.util.Objects;
import java.util.UUID;

/**
 * Um lado de um lançamento. Crédito aumenta o saldo da conta, débito diminui. A descrição é o texto
 * que aparece no extrato daquela conta; nula, vale a da transação.
 */
public record Posting(UUID accountId, Direction direction, long amountCents, String description) {

    public enum Direction { DEBIT, CREDIT }

    public Posting {
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(direction);
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be positive");
        }
    }

    public static Posting debit(UUID accountId, long amountCents) {
        return debit(accountId, amountCents, null);
    }

    public static Posting debit(UUID accountId, long amountCents, String description) {
        return new Posting(accountId, Direction.DEBIT, amountCents, description);
    }

    public static Posting credit(UUID accountId, long amountCents) {
        return credit(accountId, amountCents, null);
    }

    public static Posting credit(UUID accountId, long amountCents, String description) {
        return new Posting(accountId, Direction.CREDIT, amountCents, description);
    }
}
