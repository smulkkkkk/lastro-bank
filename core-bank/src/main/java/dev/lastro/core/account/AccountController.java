package dev.lastro.core.account;

import dev.lastro.core.account.AccountRepository.Account;
import dev.lastro.core.account.AccountRepository.PostedDeposit;
import dev.lastro.core.account.AccountService.Page;
import dev.lastro.core.ledger.LedgerService.Kind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
class AccountController {

    record DepositRequest(@NotNull @Positive @Max(AccountService.DAILY_DEPOSIT_LIMIT_CENTS) Long amountCents) {
    }

    private final AccountService accounts;

    AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    List<Account> list(@AuthenticationPrincipal Jwt user) {
        return accounts.list(ownerId(user));
    }

    @GetMapping("/{id}")
    Account get(@PathVariable UUID id, @AuthenticationPrincipal Jwt user) {
        return accounts.get(id, ownerId(user));
    }

    @PostMapping("/{id}/deposits")
    PostedDeposit deposit(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String idempotencyKey,
            @Valid @RequestBody DepositRequest request,
            @AuthenticationPrincipal Jwt user) {
        return accounts.deposit(id, ownerId(user), request.amountCents(), idempotencyKey);
    }

    @GetMapping("/{id}/statement")
    Page statement(@PathVariable UUID id,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(required = false) Kind kind,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @AuthenticationPrincipal Jwt user) {
        return accounts.statement(id, ownerId(user), from, to, kind, before, limit);
    }

    private static UUID ownerId(Jwt user) {
        return UUID.fromString(user.getSubject());
    }
}
