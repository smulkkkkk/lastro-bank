package dev.lastro.core.ledger;

import java.util.Objects;
import java.util.UUID;

/** Um lado de um lançamento. Crédito aumenta o saldo da conta, débito diminui. */
public record Posting(UUID accountId, Direction direction, long amountCents) {

    public enum Direction { DEBIT, CREDIT }

    public Posting {
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(direction);
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be positive");
        }
    }

    public static Posting debit(UUID accountId, long amountCents) {
        return new Posting(accountId, Direction.DEBIT, amountCents);
    }

    public static Posting credit(UUID accountId, long amountCents) {
        return new Posting(accountId, Direction.CREDIT, amountCents);
    }
}
