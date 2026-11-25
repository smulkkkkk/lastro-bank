package dev.lastro.core.pix;

import dev.lastro.core.pix.PixRepository.Contact;
import dev.lastro.core.pix.PixRepository.Key;
import dev.lastro.core.pix.PixRepository.Receipt;
import dev.lastro.core.pix.PixService.KeyKind;
import dev.lastro.core.pix.PixService.Limits;
import dev.lastro.core.pix.PixService.Recipient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
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
@RequestMapping("/api/v1/pix")
class PixController {

    record KeyRequest(@NotNull KeyKind kind) {
    }

    record LookupRequest(@NotBlank @Size(max = 254) String key) {
    }

    record TransferRequest(
            @NotBlank @Size(max = 254) String key,
            @NotNull @Positive @Max(PixService.DAY_LIMIT_CENTS) Long amountCents,
            @Size(max = 80) String message) {
    }

    private final PixService pix;

    PixController(PixService pix) {
        this.pix = pix;
    }

    @GetMapping("/keys")
    List<Key> keys(@AuthenticationPrincipal Jwt user) {
        return pix.keys(ownerId(user));
    }

    @PostMapping("/keys")
    @ResponseStatus(HttpStatus.CREATED)
    Key createKey(@Valid @RequestBody KeyRequest request, @AuthenticationPrincipal Jwt user) {
        return pix.createKey(ownerId(user), request.kind());
    }

    @DeleteMapping("/keys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteKey(@PathVariable UUID id, @AuthenticationPrincipal Jwt user) {
        pix.deleteKey(ownerId(user), id);
    }

    /** POST porque a chave é dado pessoal e não deve aparecer em URL nem em log de acesso. */
    @PostMapping("/lookup")
    Recipient lookup(@Valid @RequestBody LookupRequest request, @AuthenticationPrincipal Jwt user) {
        return pix.lookup(ownerId(user), request.key());
    }

    @GetMapping("/limits")
    Limits limits(@AuthenticationPrincipal Jwt user) {
        return pix.limits(ownerId(user));
    }

    @GetMapping("/contacts")
    List<Contact> contacts(@AuthenticationPrincipal Jwt user) {
        return pix.contacts(ownerId(user));
    }

    @PostMapping("/transfers")
    Receipt transfer(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String idempotencyKey,
            @Valid @RequestBody TransferRequest request,
            @AuthenticationPrincipal Jwt user) {
        return pix.transfer(ownerId(user), request.key(), request.amountCents(), request.message(), idempotencyKey);
    }

    private static UUID ownerId(Jwt user) {
        return UUID.fromString(user.getSubject());
    }
}
