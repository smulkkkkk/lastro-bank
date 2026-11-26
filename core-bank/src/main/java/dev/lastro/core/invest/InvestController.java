package dev.lastro.core.invest;

import dev.lastro.core.invest.InvestService.Market;
import dev.lastro.core.invest.InvestService.Portfolio;
import dev.lastro.core.invest.InvestService.Trade;
import dev.lastro.core.invest.MarketSource.News;
import dev.lastro.core.invest.MarketSource.Point;
import dev.lastro.core.shared.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
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
class InvestController {

    private static final String KEY = "[A-Za-z0-9_-]{8,64}";
    private static final Set<Integer> PERIODS = Set.of(7, 30, 90);

    record BuyRequest(@NotNull Asset asset,
            @NotNull @Min(InvestService.MIN_CENTS) @Max(InvestService.MAX_CENTS) Long amountCents,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal expectedPrice) {
    }

    record SellRequest(@NotNull Asset asset, @Min(1) @Max(InvestService.MAX_CENTS) Long amountCents, boolean all,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal expectedPrice) {
    }

    private final InvestService invest;
    private final MarketService market;

    InvestController(InvestService invest, MarketService market) {
        this.invest = invest;
        this.market = market;
    }

    @GetMapping("/market")
    Market quotes() {
        return invest.market();
    }

    @GetMapping("/market/{asset}/history")
    List<Point> history(@PathVariable Asset asset, @RequestParam(defaultValue = "30") int days) {
        if (!PERIODS.contains(days)) {
            throw ApiException.badRequest("request.invalid", "Escolha 7, 30 ou 90 dias.");
        }
        return market.history(asset, days);
    }

    @GetMapping("/market/news")
    List<News> news() {
        return market.news();
    }

    @GetMapping("/invest")
    Portfolio portfolio(@AuthenticationPrincipal Jwt user) {
        return invest.portfolio(ownerId(user));
    }

    @PostMapping("/invest/buy")
    Trade buy(@RequestHeader("Idempotency-Key") @Pattern(regexp = KEY) String key,
            @Valid @RequestBody BuyRequest request, @AuthenticationPrincipal Jwt user) {
        return invest.buy(ownerId(user), request.asset(), request.amountCents(), request.expectedPrice(), key);
    }

    @PostMapping("/invest/sell")
    Trade sell(@RequestHeader("Idempotency-Key") @Pattern(regexp = KEY) String key,
            @Valid @RequestBody SellRequest request, @AuthenticationPrincipal Jwt user) {
        if (request.all() == (request.amountCents() != null)) {
            throw ApiException.badRequest("request.invalid", "Informe um valor ou peça para vender tudo.");
        }
        return invest.sell(ownerId(user), request.asset(), request.amountCents(), request.all(),
                request.expectedPrice(), key);
    }

    private static UUID ownerId(Jwt user) {
        return UUID.fromString(user.getSubject());
    }
}
