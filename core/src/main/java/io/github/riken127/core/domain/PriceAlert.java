package io.github.riken127.core.domain;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;

@Value
@Builder(toBuilder = true)
public class PriceAlert {
    String id;
    String symbol;
    BigDecimal targetPrice;
    AlertDirection direction;
    AlertStatus status;
    OffsetDateTime createdAt;
    OffsetDateTime triggeredAt;   // null until fired

    public boolean isActive() {
        return status == AlertStatus.ACTIVE;
    }

    public boolean isTriggered(BigDecimal currentPrice) {
        return isActive() && direction.isTriggered(currentPrice, targetPrice);
    }
}
