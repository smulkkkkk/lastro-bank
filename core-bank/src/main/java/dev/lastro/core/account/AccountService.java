package dev.lastro.core.account;

import dev.lastro.core.account.AccountRepository.Account;
import dev.lastro.core.account.AccountRepository.PostedDeposit;
import dev.lastro.core.account.AccountRepository.StatementLine;
import dev.lastro.core.account.AccountRepository.TracePart;
import dev.lastro.core.account.AccountRepository.TracedEntry;
import dev.lastro.core.card.CardService;
import dev.lastro.core.ledger.LedgerService;
import dev.lastro.core.ledger.LedgerService.Kind;
import dev.lastro.core.ledger.Posting;
import dev.lastro.core.shared.ApiException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    static final long WELCOME_CENTS = 1_000_00;
    static final long CREDIT_LIMIT_CENTS = 3_000_00;
    static final long DAILY_DEPOSIT_LIMIT_CENTS = 5_000_00;

    record Page(List<StatementLine> items, Long nextCursor) {
    }

    record Trace(String direction, long amountCents, long remainingCents, List<TracePart> items) {
    }

    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final CardService cards;

    AccountService(AccountRepository accounts, LedgerService ledger, CardService cards) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.cards = cards;
    }

    /** Faz parte da transação do cadastro: usuário sem conta nunca existe. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void openChecking(UUID ownerId) {
        UUID accountId = UUID.randomUUID();
        accounts.insert(accountId, ownerId, "CHECKING", AccountNumber.format(accounts.nextNumber()), 0);
        accounts.insert(UUID.randomUUID(), ownerId, "CREDIT", "CR-" + accounts.nextNumber(), CREDIT_LIMIT_CENTS);
        cards.issueFor(accountId);
        ledger.post(Kind.WELCOME, "Saldo de boas-vindas", "welcome:" + accountId, List.of(
                Posting.debit(LedgerService.FUNDING_ACCOUNT, WELCOME_CENTS),
                Posting.credit(accountId, WELCOME_CENTS)));
    }

    /** Conta vazia para uma caixinha; quem chama grava o resto na mesma transação. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID openVault(UUID ownerId) {
        UUID accountId = UUID.randomUUID();
        accounts.insert(accountId, ownerId, "VAULT", AccountNumber.format(accounts.nextNumber()), 0);
        return accountId;
    }

    public UUID checkingId(UUID ownerId) {
        return accounts.idByKind(ownerId, "CHECKING");
    }

    public UUID creditId(UUID ownerId) {
        return accounts.idByKind(ownerId, "CREDIT");
    }

    /** Conta da dívida de um empréstimo; o limite dela é o valor emprestado. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID openLoan(UUID ownerId, long principalCents) {
        UUID accountId = UUID.randomUUID();
        accounts.insert(accountId, ownerId, "LOAN", "EM-" + accounts.nextNumber(), principalCents);
        return accountId;
    }

    List<Account> list(UUID ownerId) {
        return accounts.findByOwner(ownerId);
    }

    Account get(UUID accountId, UUID ownerId) {
        return accounts.findOwned(accountId, ownerId)
                .orElseThrow(() -> ApiException.notFound("account.not_found", "Conta não encontrada."));
    }

    @Transactional
    public PostedDeposit deposit(UUID accountId, UUID ownerId, long amountCents, String idempotencyKey) {
        Account account = get(accountId, ownerId);
        if (!"CHECKING".equals(account.kind())) {
            throw ApiException.unprocessable("deposit.account_kind", "Depósitos só entram na conta corrente.");
        }
        // A trava vem antes da busca: dois envios iguais ao mesmo tempo entram em fila.
        ledger.lock(List.of(LedgerService.FUNDING_ACCOUNT, accountId));

        String externalRef = "deposit:" + accountId + ":" + idempotencyKey;
        Optional<PostedDeposit> previous = accounts.findDeposit(externalRef, accountId);
        if (previous.isPresent()) {
            if (previous.get().amountCents() != amountCents) {
                throw ApiException.conflict("idempotency.mismatch",
                        "Esta Idempotency-Key já foi usada com outro valor.");
            }
            return previous.get();
        }
        if (accounts.depositedToday(accountId) + amountCents > DAILY_DEPOSIT_LIMIT_CENTS) {
            throw ApiException.unprocessable("deposit.daily_limit", "Limite diário de depósito atingido.");
        }
        UUID transactionId = ledger.post(Kind.DEPOSIT, "Depósito simulado", externalRef, List.of(
                Posting.debit(LedgerService.FUNDING_ACCOUNT, amountCents),
                Posting.credit(accountId, amountCents)));
        return new PostedDeposit(transactionId, amountCents);
    }

    /** Rastro: de onde veio uma saída, ou para onde foi uma entrada e quanto dela ainda está na conta. */
    Trace trace(UUID accountId, UUID ownerId, long entryId) {
        get(accountId, ownerId);
        TracedEntry entry = accounts.findEntry(accountId, entryId)
                .orElseThrow(() -> ApiException.notFound("entry.not_found", "Lançamento não encontrado."));
        List<TracePart> parts = accounts.trace(entryId);
        long explained = parts.stream().mapToLong(TracePart::partCents).sum();
        return new Trace(entry.direction(), entry.amountCents(), entry.amountCents() - explained, parts);
    }

    Page statement(UUID accountId, UUID ownerId, OffsetDateTime from, OffsetDateTime to, Kind kind,
            Long before, int limit) {
        get(accountId, ownerId);
        // Pede uma linha a mais só para saber se existe próxima página.
        List<StatementLine> lines =
                accounts.statement(accountId, from, to, kind == null ? null : kind.name(), before, limit + 1);
        if (lines.size() <= limit) {
            return new Page(lines, null);
        }
        List<StatementLine> page = lines.subList(0, limit);
        return new Page(page, page.getLast().entryId());
    }
}
