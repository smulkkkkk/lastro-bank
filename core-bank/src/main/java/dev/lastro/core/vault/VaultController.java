package dev.lastro.core.vault;

import dev.lastro.core.vault.VaultService.Overview;
import dev.lastro.core.vault.VaultService.Vault;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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
@RequestMapping("/api/v1/vaults")
class VaultController {

    private static final long MAX_CENTS = 999_999_999;
    private static final String KEY = "[A-Za-z0-9_-]{8,64}";

    record CreateRequest(@NotBlank @Size(max = 40) String name, @Positive @Max(MAX_CENTS) Long goalCents,
            LocalDate lockedUntil) {
    }

    record MoveRequest(@NotNull @Positive @Max(MAX_CENTS) Long amountCents) {
    }

    private final VaultService vaults;

    VaultController(VaultService vaults) {
        this.vaults = vaults;
    }

    @GetMapping
    Overview overview(@AuthenticationPrincipal Jwt user) {
        return vaults.overview(ownerId(user));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Vault create(@Valid @RequestBody CreateRequest request, @AuthenticationPrincipal Jwt user) {
        return vaults.create(ownerId(user), request.name(), request.goalCents(), request.lockedUntil());
    }

    @PostMapping("/{id}/deposits")
    Vault deposit(@PathVariable UUID id, @RequestHeader("Idempotency-Key") @Pattern(regexp = KEY) String key,
            @Valid @RequestBody MoveRequest request, @AuthenticationPrincipal Jwt user) {
        return vaults.deposit(id, ownerId(user), request.amountCents(), key);
    }

    @PostMapping("/{id}/withdrawals")
    Vault withdraw(@PathVariable UUID id, @RequestHeader("Idempotency-Key") @Pattern(regexp = KEY) String key,
            @Valid @RequestBody MoveRequest request, @AuthenticationPrincipal Jwt user) {
        return vaults.withdraw(id, ownerId(user), request.amountCents(), key);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@PathVariable UUID id, @AuthenticationPrincipal Jwt user) {
        vaults.archive(id, ownerId(user));
    }

    private static UUID ownerId(Jwt user) {
        return UUID.fromString(user.getSubject());
    }
}
