package dev.lastro.core.openfinance;

import dev.lastro.core.openfinance.OpenFinanceService.Bank;
import dev.lastro.core.openfinance.OpenFinanceService.Consent;
import dev.lastro.core.openfinance.OpenFinanceService.ExternalTransaction;
import dev.lastro.core.openfinance.OpenFinanceService.Permission;
import dev.lastro.core.openfinance.OpenFinanceService.Transfer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/open-finance")
class OpenFinanceController {

    private static final String KEY = "[A-Za-z0-9_-]{8,64}";

    record ConsentRequest(@NotBlank @Pattern(regexp = "[A-Z]{2,20}") String bank, @NotEmpty Set<Permission> permissions,
            @NotNull Integer months) {
    }

    record AuthorizeRequest(@NotNull Boolean approve) {
    }

    record TransferRequest(@NotNull @Positive Long amountCents) {
    }

    private final OpenFinanceService openFinance;

    OpenFinanceController(OpenFinanceService openFinance) {
        this.openFinance = openFinance;
    }

    @GetMapping("/banks")
    List<Bank> banks() {
        return openFinance.banks();
    }

    @GetMapping("/consents")
    List<Consent> consents(@AuthenticationPrincipal Jwt user) {
        return openFinance.consents(ownerId(user));
    }

    @PostMapping("/consents")
    @ResponseStatus(HttpStatus.CREATED)
    Consent request(@Valid @RequestBody ConsentRequest request, @AuthenticationPrincipal Jwt user) {
        return openFinance.request(ownerId(user), request.bank(), request.permissions(), request.months());
    }

    @PostMapping("/consents/{id}/authorization")
    Consent authorize(@PathVariable UUID id, @Valid @RequestBody AuthorizeRequest request, @AuthenticationPrincipal Jwt user) {
        return openFinance.authorize(ownerId(user), id, request.approve());
    }

    @DeleteMapping("/consents/{id}")
    Consent revoke(@PathVariable UUID id, @AuthenticationPrincipal Jwt user) {
        return openFinance.revoke(ownerId(user), id);
    }

    @GetMapping("/consents/{id}/transactions")
    List<ExternalTransaction> transactions(@PathVariable UUID id, @AuthenticationPrincipal Jwt user) {
        return openFinance.transactions(ownerId(user), id);
    }

    @PostMapping("/consents/{id}/transfers")
    Transfer bring(@RequestHeader("Idempotency-Key") @Pattern(regexp = KEY) String key, @PathVariable UUID id,
            @Valid @RequestBody TransferRequest request, @AuthenticationPrincipal Jwt user) {
        return openFinance.bring(ownerId(user), id, request.amountCents(), key);
    }

    private static UUID ownerId(Jwt user) {
        return UUID.fromString(user.getSubject());
    }
}
