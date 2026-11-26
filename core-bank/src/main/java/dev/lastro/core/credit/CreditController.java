package dev.lastro.core.credit;

import dev.lastro.core.credit.CreditService.InvoiceItem;
import dev.lastro.core.credit.CreditService.Mode;
import dev.lastro.core.credit.CreditService.Overview;
import dev.lastro.core.credit.CreditService.Purchase;
import dev.lastro.core.credit.CreditService.Quote;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.YearMonth;
import java.util.Arrays;
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
@RequestMapping("/api/v1")
class CreditController {

    private static final long MAX_CENTS = 999_999_999;
    private static final String KEY = "[A-Za-z0-9_-]{8,64}";

    record MerchantView(Merchant code, String name, String category) {
    }

    record PurchaseRequest(@NotNull Merchant merchant, @NotNull @Positive @Max(MAX_CENTS) Long amountCents,
            @NotNull Mode mode, @NotNull @Min(1) @Max(12) Integer installments, boolean contactless) {
    }

    record PaymentRequest(@NotNull @Positive @Max(MAX_CENTS) Long amountCents) {
    }

    record ConversionRequest(@NotNull @Min(CreditService.MIN_CONVERSION_CENTS) @Max(MAX_CENTS) Long amountCents,
            @NotNull @Min(1) @Max(CreditService.MAX_CONVERSION_INSTALLMENTS) Integer installments,
            @NotNull @Positive Long acceptedTotalCents) {
    }

    private final CreditService credit;

    CreditController(CreditService credit) {
        this.credit = credit;
    }

    @GetMapping("/merchants")
    List<MerchantView> merchants() {
        return Arrays.stream(Merchant.values()).map(m -> new MerchantView(m, m.title(), m.category())).toList();
    }

    @GetMapping("/purchases")
    List<Purchase> purchases(@AuthenticationPrincipal Jwt user) {
        return credit.purchases(ownerId(user));
    }

    @PostMapping("/cards/{id}/purchases")
    Purchase buy(@PathVariable UUID id, @RequestHeader("Idempotency-Key") @Pattern(regexp = KEY) String key,
            @Valid @RequestBody PurchaseRequest request, @AuthenticationPrincipal Jwt user) {
        return credit.buy(ownerId(user), id, request.merchant(), request.amountCents(), request.mode(),
                request.installments(), request.contactless(), key);
    }

    @GetMapping("/credit")
    Overview overview(@AuthenticationPrincipal Jwt user) {
        return credit.overview(ownerId(user));
    }

    @GetMapping("/credit/invoices/{month}/items")
    List<InvoiceItem> items(@PathVariable YearMonth month, @AuthenticationPrincipal Jwt user) {
        return credit.items(ownerId(user), month.atDay(1));
    }

    @PostMapping("/credit/invoices/{month}/payments")
    Overview pay(@PathVariable YearMonth month, @RequestHeader("Idempotency-Key") @Pattern(regexp = KEY) String key,
            @Valid @RequestBody PaymentRequest request, @AuthenticationPrincipal Jwt user) {
        return credit.pay(ownerId(user), month.atDay(1), request.amountCents(), key);
    }

    @GetMapping("/credit/conversion")
    Quote quote(@RequestParam @Min(CreditService.MIN_CONVERSION_CENTS) @Max(MAX_CENTS) long amountCents,
            @RequestParam @Min(1) @Max(CreditService.MAX_CONVERSION_INSTALLMENTS) int installments) {
        return credit.quote(amountCents, installments);
    }

    @PostMapping("/credit/conversions")
    Purchase convert(@RequestHeader("Idempotency-Key") @Pattern(regexp = KEY) String key,
            @Valid @RequestBody ConversionRequest request, @AuthenticationPrincipal Jwt user) {
        return credit.convert(ownerId(user), request.amountCents(), request.installments(),
                request.acceptedTotalCents(), key);
    }

    private static UUID ownerId(Jwt user) {
        return UUID.fromString(user.getSubject());
    }
}
