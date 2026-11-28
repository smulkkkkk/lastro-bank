package dev.lastro.core.reward;

import dev.lastro.core.credit.Merchant;
import dev.lastro.core.reward.RewardService.Coupon;
import dev.lastro.core.reward.RewardService.Store;
import dev.lastro.core.reward.RewardService.Wallet;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
@RequestMapping("/api/v1/rewards")
class RewardController {

    private static final String KEY = "[A-Za-z0-9_-]{8,64}";

    record RedeemRequest(@NotNull @Max(100_000_000) Long bubbles) {
    }

    private final RewardService rewards;

    RewardController(RewardService rewards) {
        this.rewards = rewards;
    }

    @GetMapping
    Wallet wallet(@AuthenticationPrincipal Jwt user) {
        return rewards.wallet(ownerId(user));
    }

    @GetMapping("/stores")
    List<Store> stores(@AuthenticationPrincipal Jwt user) {
        return rewards.stores(ownerId(user), Merchant.catalog());
    }

    @PostMapping("/stores/{merchant}/coupon")
    @ResponseStatus(HttpStatus.CREATED)
    Coupon claim(@PathVariable @Pattern(regexp = "[A-Z_]{2,20}") String merchant, @AuthenticationPrincipal Jwt user) {
        return rewards.claim(ownerId(user), merchant);
    }

    @PostMapping("/redeem")
    Wallet redeem(@RequestHeader("Idempotency-Key") @Pattern(regexp = KEY) String key,
            @Valid @RequestBody RedeemRequest request, @AuthenticationPrincipal Jwt user) {
        return rewards.redeem(ownerId(user), request.bubbles(), key);
    }

    private static UUID ownerId(Jwt user) {
        return UUID.fromString(user.getSubject());
    }
}
