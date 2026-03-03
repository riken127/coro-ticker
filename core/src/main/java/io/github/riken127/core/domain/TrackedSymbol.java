package io.github.riken127.core.domain;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class TrackedSymbol {
    String symbol;
    String exchange;
    int subscribers;           // live WebSocket count from engine metrics
}