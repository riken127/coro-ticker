package io.github.riken127.core.domain;

import java.math.BigDecimal;

public enum AlertDirection {
    ABOVE,  // trigger when price rises above threshold
    BELOW;  // trigger when price falls below threshold

    public boolean isTriggered(BigDecimal current, BigDecimal target) {
        return switch (this) {
            case ABOVE -> current.compareTo(target) >= 0;
            case BELOW -> current.compareTo(target) <= 0;
        };
    }
}