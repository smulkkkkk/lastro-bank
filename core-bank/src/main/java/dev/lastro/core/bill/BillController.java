package dev.lastro.core.bill;

import dev.lastro.core.bill.BillService.BoletoInfo;
import dev.lastro.core.bill.BillService.Operator;
import dev.lastro.core.bill.BillService.OperatorInfo;
import dev.lastro.core.bill.BillService.Payment;
import dev.lastro.core.bill.BillService.Receipt;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bills")
class BillController {

    private static final String KEY = "[A-Za-z0-9_-]{8,64}";

    record BoletoRequest(@NotBlank @Size(max = 80) String line) {
    }

    /** Celular com DDD: 11 dígitos, o terceiro é 9. */
    record TopUpRequest(@NotNull Operator operator, @NotBlank @Pattern(regexp = "[1-9][0-9]9[0-9]{8}") String phone,
            @NotNull Long amountCents) {
    }

    private final BillService bills;

    BillController(BillService bills) {
        this.bills = bills;
    }

    @GetMapping
    List<Payment> recent(@AuthenticationPrincipal Jwt user) {
        return bills.recent(ownerId(user));
    }

    @PostMapping("/boletos/lookup")
    BoletoInfo lookup(@Valid @RequestBody BoletoRequest request) {
        return bills.lookup(request.line());
    }

    @GetMapping("/{id}")
    Receipt receipt(@PathVariable UUID id, @AuthenticationPrincipal Jwt user) {
        return bills.receipt(ownerId(user), id);
    }

    @PostMapping("/boletos/pay")
    Receipt pay(@RequestHeader("Idempotency-Key") @Pattern(regexp = KEY) String key,
            @Valid @RequestBody BoletoRequest request, @AuthenticationPrincipal Jwt user) {
        UUID owner = ownerId(user);
        return bills.receipt(owner, bills.payBoleto(owner, request.line(), key).id());
    }

    @PostMapping("/boletos/sample")
    @ResponseStatus(HttpStatus.CREATED)
    BoletoInfo sample(@AuthenticationPrincipal Jwt user) {
        return bills.sample(ownerId(user));
    }

    @GetMapping("/operators")
    List<OperatorInfo> operators() {
        return bills.operators();
    }

    @PostMapping("/top-ups")
    Receipt topUp(@RequestHeader("Idempotency-Key") @Pattern(regexp = KEY) String key,
            @Valid @RequestBody TopUpRequest request, @AuthenticationPrincipal Jwt user) {
        UUID owner = ownerId(user);
        return bills.receipt(owner, bills.topUp(owner, request.operator(), request.phone(), request.amountCents(), key).id());
    }

    private static UUID ownerId(Jwt user) {
        return UUID.fromString(user.getSubject());
    }
}
