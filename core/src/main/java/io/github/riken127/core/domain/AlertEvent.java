package io.github.riken127.core.domain;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;

@Value
@Builder
public class AlertEvent {
    PriceAlert alert;
    BigDecimal triggerPrice;   // actual price that crossed the threshold
    OffsetDateTime triggeredAt;
}