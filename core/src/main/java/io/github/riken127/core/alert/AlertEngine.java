package io.github.riken127.core.alert;

import io.github.riken127.core.domain.*;
import io.github.riken127.core.engine.MarketDataEngine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Log4j2
public class AlertEngine {

    private final MarketDataEngine marketDataEngine;
    private final MeterRegistry meterRegistry;

    private final ConcurrentHashMap<String, PriceAlert> alerts =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Sinks.One<AlertEvent>> alertSinks =
            new ConcurrentHashMap<>();

    public AlertEngine(MarketDataEngine marketDataEngine, MeterRegistry meterRegistry) {
        this.marketDataEngine = marketDataEngine;
        this.meterRegistry    = meterRegistry;
    }

    @PostConstruct
    void start() {
        marketDataEngine.broadcastStream()
                .publishOn(Schedulers.boundedElastic())
                .subscribe(this::evaluate);

        Gauge.builder("alerts.active", alerts, map ->
                        map.values().stream()
                                .filter(PriceAlert::isActive)
                                .count())
                .description("Number of active price alerts")
                .register(meterRegistry);

        log.info("[AlertEngine] Started — watching broadcast stream");
    }

    /**
     * Registers a new alert and returns it.
     * The caller can immediately subscribe to streamAlert(id) — the sink is
     * created here so no race condition between register and subscribe.
     */
    public PriceAlert registerAlert(String symbol, BigDecimal targetPrice,
                                    AlertDirection direction) {
        String id = UUID.randomUUID().toString();

        PriceAlert alert = PriceAlert.builder()
                .id(id)
                .symbol(symbol.toUpperCase())
                .targetPrice(targetPrice)
                .direction(direction)
                .status(AlertStatus.ACTIVE)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        alertSinks.put(id, Sinks.one());
        alerts.put(id, alert);

        meterRegistry.counter("alerts.registered",
                "symbol",    alert.getSymbol(),
                "direction", direction.name()
        ).increment();

        log.info("[AlertEngine] Alert registered — id={} symbol={} {} {}",
                id, symbol, direction, targetPrice);

        return alert;
    }

    /**
     * Subscribes to a single alert — Mono completes when the alert fires or is cancelled.
     * Late subscribers still receive the event if it already fired (Sinks.One replays).
     */
    public Mono<AlertEvent> streamAlert(String alertId) {
        Sinks.One<AlertEvent> sink = alertSinks.get(alertId);
        if (sink == null) {
            return Mono.error(new IllegalArgumentException(
                    "No alert found for id: " + alertId));
        }
        return sink.asMono();
    }

    /** Cancels an active alert — completes its sink with empty so subscribers unblock */
    public boolean cancelAlert(String alertId) {
        PriceAlert alert = alerts.get(alertId);
        if (alert == null || !alert.isActive()) return false;

        alerts.put(alertId, alert.toBuilder()
                .status(AlertStatus.CANCELLED)
                .build());

        Sinks.One<AlertEvent> sink = alertSinks.remove(alertId);
        if (sink != null) sink.tryEmitEmpty();

        meterRegistry.counter("alerts.cancelled").increment();
        log.info("[AlertEngine] Alert cancelled — id={}", alertId);
        return true;
    }

    public List<PriceAlert> getActiveAlerts() {
        return alerts.values().stream()
                .filter(PriceAlert::isActive)
                .toList();
    }

    public Optional<PriceAlert> getAlert(String alertId) {
        return Optional.ofNullable(alerts.get(alertId));
    }

    private void evaluate(Asset asset) {
        alerts.forEach((id, alert) -> {
            if (!alert.isActive()) return;
            if (!alert.getSymbol().equalsIgnoreCase(asset.getSymbol())) return;
            if (!alert.isTriggered(asset.getCurrentPrice())) return;

            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            PriceAlert triggered = alert.toBuilder()
                    .status(AlertStatus.TRIGGERED)
                    .triggeredAt(now)
                    .build();

            boolean swapped = alerts.replace(id, alert, triggered);
            if (!swapped) return;

            AlertEvent event = AlertEvent.builder()
                    .alert(triggered)
                    .triggerPrice(asset.getCurrentPrice())
                    .triggeredAt(now)
                    .build();

            Sinks.One<AlertEvent> sink = alertSinks.remove(id);
            if (sink != null) {
                sink.tryEmitValue(event);
            }

            meterRegistry.counter("alerts.triggered",
                    "symbol",    alert.getSymbol(),
                    "direction", alert.getDirection().name()
            ).increment();

            log.info("[AlertEngine] Alert triggered — id={} symbol={} price={}",
                    id, alert.getSymbol(), asset.getCurrentPrice());
        });
    }
}
