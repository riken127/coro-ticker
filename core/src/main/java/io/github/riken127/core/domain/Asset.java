package io.github.riken127.core.domain;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;

@Value
@Builder(toBuilder = true)
public class Asset {
    String symbol;
    String name;
    String exchange;

    long seqNo;

    BigDecimal currentPrice;
    BigDecimal bidPrice;
    BigDecimal askPrice;

    BigDecimal change24h;
    BigDecimal changePct24h;
    BigDecimal high24h;
    BigDecimal low24h;
    BigDecimal volume24h;

    OffsetDateTime lastUpdated;

    /** Merge a live tick into an existing snapshot — preserves 24h fields if tick doesn't carry them */
    public Asset mergeWith(Asset tick) {
        return this.toBuilder()
                .currentPrice(tick.currentPrice)
                .bidPrice(tick.bidPrice != null ? tick.bidPrice : this.bidPrice)
                .askPrice(tick.askPrice != null ? tick.askPrice : this.askPrice)
                .change24h(tick.change24h != null ? tick.change24h : this.change24h)
                .changePct24h(tick.changePct24h != null ? tick.changePct24h : this.changePct24h)
                .high24h(tick.high24h != null ? tick.high24h : this.high24h)
                .low24h(tick.low24h != null ? tick.low24h : this.low24h)
                .volume24h(tick.volume24h != null ? tick.volume24h : this.volume24h)
                .seqNo(tick.seqNo)
                .lastUpdated(tick.lastUpdated)
                .build();
    }

    /** Unique routing key used by the engine — isolates same symbol across exchanges */
    public String routingKey() {
        return exchange.toUpperCase() + ":" + symbol.toUpperCase();
    }
}
