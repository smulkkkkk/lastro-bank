package dev.lastro.core.loan;

import dev.lastro.core.loan.LoanService.Loan;
import dev.lastro.core.loan.LoanService.Overview;
import dev.lastro.core.loan.LoanService.Payment;
import dev.lastro.core.loan.LoanService.Quote;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/loans")
class LoanController {

    private static final String KEY = "[A-Za-z0-9_-]{8,64}";

    record TakeRequest(@NotNull @Min(LoanService.MIN_CENTS) @Max(LoanService.MAX_CENTS) Long amountCents,
            @NotNull @Min(LoanService.MIN_INSTALLMENTS) @Max(LoanService.MAX_INSTALLMENTS) Integer installments,
            @NotNull @Positive Long acceptedTotalCents) {
    }

    record PayRequest(@NotNull Payment kind, @NotNull @Positive Long expectedCents) {
    }

    private final LoanService loans;

    LoanController(LoanService loans) {
        this.loans = loans;
    }

    @GetMapping
    Overview overview(@AuthenticationPrincipal Jwt user) {
        return loans.overview(ownerId(user));
    }

    @GetMapping("/quote")
    Quote quote(@RequestParam @Min(LoanService.MIN_CENTS) @Max(LoanService.MAX_CENTS) long amountCents,
            @RequestParam @Min(LoanService.MIN_INSTALLMENTS) @Max(LoanService.MAX_INSTALLMENTS) int installments) {
        return loans.quote(amountCents, installments);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Loan take(@RequestHeader("Idempotency-Key") @Pattern(regexp = KEY) String key,
            @Valid @RequestBody TakeRequest request, @AuthenticationPrincipal Jwt user) {
        return loans.take(ownerId(user), request.amountCents(), request.installments(),
                request.acceptedTotalCents(), key);
    }

    @PostMapping("/{id}/payments")
    Loan pay(@PathVariable UUID id, @RequestHeader("Idempotency-Key") @Pattern(regexp = KEY) String key,
            @Valid @RequestBody PayRequest request, @AuthenticationPrincipal Jwt user) {
        return loans.pay(id, ownerId(user), request.kind(), request.expectedCents(), key);
    }

    private static UUID ownerId(Jwt user) {
        return UUID.fromString(user.getSubject());
    }
}
