package io.github.riken127.core.provider;

import io.github.riken127.core.domain.Asset;
import lombok.extern.log4j.Log4j2;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * aggTrade and miniTicker arrive independently and at different rates.
 * This accumulator merges both into a single Asset before emitting.
 * tryBuild() returns null until at least a price has been seen — safe to filter.
 */
@Log4j2
class AssetAccumulator {

    private final String symbol;

    private volatile BigDecimal currentPrice;

    private volatile BigDecimal high24h;
    private volatile BigDecimal low24h;
    private volatile BigDecimal volume24h;
    private volatile BigDecimal change24h;
    private volatile BigDecimal changePct24h;

    AssetAccumulator(String symbol) {
        this.symbol = symbol;
    }

    AssetAccumulator applyTrade(BigDecimal price) {
        this.currentPrice = price;
        return this;
    }

    AssetAccumulator applyTicker(BigDecimal high, BigDecimal low, BigDecimal volume,
                                 BigDecimal change, BigDecimal changePct) {
        this.high24h      = high;
        this.low24h       = low;
        this.volume24h    = volume;
        this.change24h    = change;
        this.changePct24h = changePct;
        return this;
    }

    /**
     * Returns null if no price yet — BinanceProvider filters these out.
     * Once price is present, emits every time (miniTicker fields are optional
     * until they arrive — engine.mergeWith() fills gaps from prior snapshot).
     */
    Asset tryBuild(String exchange) {
        if (currentPrice == null) return null;

        return Asset.builder()
                .exchange(exchange)
                .symbol(symbol)
                .name(symbol.replace("USDT", ""))
                .currentPrice(currentPrice)
                .high24h(high24h)
                .low24h(low24h)
                .volume24h(volume24h)
                .change24h(change24h)
                .changePct24h(changePct24h)
                .lastUpdated(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }
}