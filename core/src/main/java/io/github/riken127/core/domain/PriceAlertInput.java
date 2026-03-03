package io.github.riken127.core.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceAlertInput {
    private String symbol;
    private BigDecimal targetPrice;
    private AlertDirection direction;
}
