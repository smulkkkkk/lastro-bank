package dev.lastro.core.invest;

import dev.lastro.core.account.AccountService;
import dev.lastro.core.invest.MarketService.Prices;
import dev.lastro.core.invest.MarketSource.Quote;
import dev.lastro.core.ledger.LedgerService;
import dev.lastro.core.ledger.LedgerService.Kind;
import dev.lastro.core.ledger.Posting;
import dev.lastro.core.shared.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InvestService {

    static final long MIN_CENTS = 10_00;
    static final long MAX_CENTS = 20_000_00;
    /** Quanto o preço pode andar entre a tela e a ordem sem pedir nova confirmação. */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    enum Side { BUY, SELL }

    record AssetView(Asset code, String name, String category, String risk, String description,
            BigDecimal bid, BigDecimal ask, BigDecimal changePercent) {
    }

    record Market(boolean stale, Instant updatedAt, List<AssetView> assets) {
    }

    record Position(Asset asset, String name, String quantity, long costCents, long valueCents, long resultCents) {
    }

    record Trade(UUID id, Asset asset, Side side, String quantity, BigDecimal price, long amountCents,
            OffsetDateTime createdAt) {
    }

    record Portfolio(long valueCents, long costCents, long resultCents, List<Position> positions, List<Trade> trades) {
    }

    private record Holding(BigDecimal quantity, long costCents) {
        static final Holding NONE = new Holding(BigDecimal.ZERO, 0);
    }

    private static final RowMapper<Trade> TRADE = (rs, n) -> new Trade(rs.getObject("id", UUID.class),
            Asset.valueOf(rs.getString("asset")), Side.valueOf(rs.getString("side")),
            rs.getBigDecimal("quantity").stripTrailingZeros().toPlainString(), rs.getBigDecimal("price"),
            rs.getLong("amount_cents"), rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;
    private final MarketService market;
    private final LedgerService ledger;
    private final AccountService accounts;
    private final Clock clock;

    InvestService(JdbcClient jdbc, MarketService market, LedgerService ledger, AccountService accounts, Clock clock) {
        this.jdbc = jdbc;
        this.market = market;
        this.ledger = ledger;
        this.accounts = accounts;
        this.clock = clock;
    }

    Market market() {
        Prices prices = market.prices();
        List<AssetView> assets = Arrays.stream(Asset.values()).map(asset -> {
            Quote quote = prices.quotes().get(asset);
            return new AssetView(asset, asset.title(), asset.category(), asset.risk(), asset.description(),
                    quote.bid(), quote.ask(), quote.changePercent());
        }).toList();
        return new Market(prices.stale(), prices.fetchedAt(), assets);
    }

    Portfolio portfolio(UUID ownerId) {
        Map<Asset, Holding> holdings = holdings(ownerId);
        Map<Asset, Quote> quotes = holdings.isEmpty() ? Map.of() : market.prices().quotes();
        List<Position> positions = holdings.entrySet().stream().map(entry -> {
            long value = valueCents(entry.getValue().quantity(), quotes.get(entry.getKey()).bid());
            return new Position(entry.getKey(), entry.getKey().title(),
                    entry.getValue().quantity().stripTrailingZeros().toPlainString(), entry.getValue().costCents(),
                    value, value - entry.getValue().costCents());
        }).toList();
        long value = positions.stream().mapToLong(Position::valueCents).sum();
        long cost = positions.stream().mapToLong(Position::costCents).sum();
        List<Trade> trades = jdbc.sql("SELECT * FROM core.trade WHERE owner_id = :owner ORDER BY created_at DESC, id LIMIT 30")
                .param("owner", ownerId).query(TRADE).list();
        return new Portfolio(value, cost, value - cost, positions, trades);
    }

    /** O preço é buscado antes da trava: chamada de rede com conta travada seguraria todo mundo. */
    @Transactional
    public Trade buy(UUID ownerId, Asset asset, long cents, BigDecimal expectedPrice, String idempotencyKey) {
        BigDecimal price = market.prices().quotes().get(asset).ask();
        UUID checking = accounts.checkingId(ownerId);
        ledger.lock(List.of(checking, LedgerService.BROKER_ACCOUNT));
        Optional<Trade> previous = repeated(ownerId, idempotencyKey, asset, Side.BUY, cents, false);
        if (previous.isPresent()) {
            return previous.get();
        }
        checkPrice(price, expectedPrice);
        BigDecimal quantity = BigDecimal.valueOf(cents).movePointLeft(2)
                .divide(price, asset.decimals(), RoundingMode.DOWN);
        if (quantity.signum() <= 0) {
            throw ApiException.unprocessable("invest.too_small", "Valor pequeno demais para este ativo.");
        }
        UUID transactionId = ledger.post(Kind.INVESTMENT_BUY, "Compra de " + asset.title(), null, List.of(
                Posting.debit(checking, cents), Posting.credit(LedgerService.BROKER_ACCOUNT, cents)));
        jdbc.sql("""
                        INSERT INTO core.position (owner_id, asset, quantity, cost_cents) VALUES (:owner, :asset, :q, :cost)
                        ON CONFLICT (owner_id, asset) DO UPDATE
                           SET quantity = position.quantity + :q, cost_cents = position.cost_cents + :cost""")
                .param("owner", ownerId).param("asset", asset.name()).param("q", quantity).param("cost", cents)
                .update();
        return save(ownerId, asset, Side.BUY, quantity, price, cents, cents, false, idempotencyKey, transactionId);
    }

    /** Vende por valor, arredondando a quantidade para cima, ou tudo. Nunca mais do que a pessoa tem. */
    @Transactional
    public Trade sell(UUID ownerId, Asset asset, Long requestedCents, boolean all, BigDecimal expectedPrice,
            String idempotencyKey) {
        BigDecimal price = market.prices().quotes().get(asset).bid();
        UUID checking = accounts.checkingId(ownerId);
        ledger.lock(List.of(checking, LedgerService.BROKER_ACCOUNT));
        Optional<Trade> previous = repeated(ownerId, idempotencyKey, asset, Side.SELL, all ? null : requestedCents, all);
        if (previous.isPresent()) {
            return previous.get();
        }
        checkPrice(price, expectedPrice);
        Holding holding = holdings(ownerId).getOrDefault(asset, Holding.NONE);
        BigDecimal quantity = all ? holding.quantity()
                : BigDecimal.valueOf(requestedCents).movePointLeft(2).divide(price, asset.decimals(), RoundingMode.UP);
        if (holding.quantity().signum() == 0 || quantity.compareTo(holding.quantity()) > 0) {
            throw ApiException.unprocessable("invest.not_enough", "Você não tem tudo isso deste ativo.");
        }
        long cents = valueCents(quantity, price);
        if (cents <= 0) {
            throw ApiException.unprocessable("invest.too_small", "Valor pequeno demais para vender.");
        }
        boolean closing = quantity.compareTo(holding.quantity()) == 0;
        long costSold = closing ? holding.costCents()
                : BigDecimal.valueOf(holding.costCents()).multiply(quantity)
                        .divide(holding.quantity(), 0, RoundingMode.HALF_UP).longValueExact();
        UUID transactionId = ledger.post(Kind.INVESTMENT_SELL, "Venda de " + asset.title(), null, List.of(
                Posting.debit(LedgerService.BROKER_ACCOUNT, cents), Posting.credit(checking, cents)));
        jdbc.sql("""
                        UPDATE core.position SET quantity = quantity - :q, cost_cents = cost_cents - :cost
                         WHERE owner_id = :owner AND asset = :asset""")
                .param("q", quantity).param("cost", costSold).param("owner", ownerId).param("asset", asset.name())
                .update();
        return save(ownerId, asset, Side.SELL, quantity, price, cents, all ? null : requestedCents, all,
                idempotencyKey, transactionId);
    }

    private static void checkPrice(BigDecimal price, BigDecimal expected) {
        if (price.subtract(expected).abs().compareTo(expected.multiply(TOLERANCE)) > 0) {
            throw ApiException.conflict("invest.price_changed",
                    "O preço mudou mais de 1% desde que você viu. Confira o novo valor.");
        }
    }

    private Optional<Trade> repeated(UUID ownerId, String key, Asset asset, Side side, Long cents, boolean all) {
        Optional<Boolean> same = jdbc.sql("""
                        SELECT asset = :asset AND side = :side AND requested_cents IS NOT DISTINCT FROM :cents
                               AND sell_all = :all
                          FROM core.trade WHERE owner_id = :owner AND idempotency_key = :key""")
                .param("asset", asset.name()).param("side", side.name()).param("cents", cents).param("all", all)
                .param("owner", ownerId).param("key", key)
                .query(Boolean.class).optional();
        if (same.isEmpty()) {
            return Optional.empty();
        }
        if (!same.get()) {
            throw ApiException.conflict("idempotency.mismatch", "Esta Idempotency-Key já foi usada em outra ordem.");
        }
        return jdbc.sql("SELECT * FROM core.trade WHERE owner_id = :owner AND idempotency_key = :key")
                .param("owner", ownerId).param("key", key).query(TRADE).optional();
    }

    private Trade save(UUID ownerId, Asset asset, Side side, BigDecimal quantity, BigDecimal price, long cents,
            Long requestedCents, boolean all, String key, UUID transactionId) {
        return jdbc.sql("""
                        INSERT INTO core.trade (id, owner_id, asset, side, quantity, price, amount_cents,
                                                requested_cents, sell_all, idempotency_key, transaction_id, created_at)
                        VALUES (:id, :owner, :asset, :side, :q, :price, :cents, :requested, :all, :key, :tx, :at)
                        RETURNING *""")
                .param("requested", requestedCents).param("all", all)
                .param("id", UUID.randomUUID()).param("owner", ownerId).param("asset", asset.name())
                .param("side", side.name()).param("q", quantity).param("price", price).param("cents", cents)
                .param("key", key).param("tx", transactionId)
                .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .query(TRADE).single();
    }

    private Map<Asset, Holding> holdings(UUID ownerId) {
        return jdbc.sql("SELECT asset, quantity, cost_cents FROM core.position WHERE owner_id = :owner AND quantity > 0")
                .param("owner", ownerId)
                .query((rs, n) -> Map.entry(Asset.valueOf(rs.getString("asset")),
                        new Holding(rs.getBigDecimal("quantity"), rs.getLong("cost_cents"))))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a,
                        () -> new java.util.EnumMap<>(Asset.class)));
    }

    /** Arredonda para baixo: o banco nunca promete um centavo que a venda não renderia. */
    static long valueCents(BigDecimal quantity, BigDecimal price) {
        return quantity.multiply(price).movePointRight(2).setScale(0, RoundingMode.DOWN).longValueExact();
    }
}
