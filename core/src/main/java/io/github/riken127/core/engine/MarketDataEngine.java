package io.github.riken127.core.engine;

import io.github.riken127.core.domain.Asset;
import io.github.riken127.core.domain.TrackedSymbol;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Log4j2
public class MarketDataEngine {
    private static final Sinks.EmitFailureHandler EMIT_HANDLER =
            Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(50));

    private final ConcurrentHashMap<String, Sinks.Many<Asset>> symbolSinks =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, AtomicLong> seqCounters =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Asset> snapshots =
            new ConcurrentHashMap<>();

    private final Sinks.Many<Asset> broadcastSink = Sinks.many()
            .multicast()
            .onBackpressureBuffer(4096, false);

    private final MeterRegistry meterRegistry;

    public MarketDataEngine(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("market.active_symbols", symbolSinks, Map::size)
                .description("Number of symbols with at least one active sink")
                .register(meterRegistry);
        Gauge.builder("market.broadcast.subscribers", broadcastSink, Sinks.Many::currentSubscriberCount)
                .description("Clients subscribed to the full broadcast stream")
                .register(meterRegistry);
    }

    public void submitUpdate(Asset rawAsset) {
        String key = rawAsset.routingKey();

        long seq = seqCounters
                .computeIfAbsent(key, k -> new AtomicLong(0))
                .incrementAndGet();

        Asset enriched = snapshots.compute(key, (k, existing) -> {
            Asset stamped = rawAsset.toBuilder().seqNo(seq).build();
            return existing == null ? stamped : existing.mergeWith(stamped);
        });

        symbolSinks
                .computeIfAbsent(key, k -> {
                    log.info("[Engine] New symbol registered: {}", k);
                    return Sinks.many().replay().latest();
                })
                .emitNext(enriched, EMIT_HANDLER);

        broadcastSink.emitNext(enriched, EMIT_HANDLER);

        meterRegistry.counter("market.updates.total",
                "symbol",   enriched.getSymbol(),
                "exchange", enriched.getExchange()
        ).increment();
    }

    /** Single symbol stream — replay(1) delivers last price immediately on subscribe */
    public Flux<Asset> streamSymbol(String exchange, String symbol) {
        String key = buildKey(exchange, symbol);
        return symbolSinks
                .computeIfAbsent(key, k -> Sinks.many().replay().latest())
                .asFlux()
                .publishOn(Schedulers.boundedElastic());
    }

    /** Multi-symbol stream — merges independent symbol streams, no cross-symbol ordering */
    public Flux<Asset> streamSymbols(String exchange, List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return broadcastSink.asFlux()
                    .publishOn(Schedulers.boundedElastic());
        }
        return Flux.merge(
                symbols.stream()
                        .map(s -> streamSymbol(exchange, s))
                        .toList()
        );
    }

    /** Returns last known snapshot for a symbol — empty if never seen */
    public Optional<Asset> latestPrice(String exchange, String symbol) {
        return Optional.ofNullable(snapshots.get(buildKey(exchange, symbol)));
    }

    /** Batch variant for latestPrices() GraphQL query */
    public List<Asset> latestPrices(String exchange, List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return List.copyOf(snapshots.values());
        }
        return symbols.stream()
                .map(s -> latestPrice(exchange, s))
                .flatMap(Optional::stream)
                .toList();
    }

    public List<TrackedSymbol> trackedSymbols() {
        return symbolSinks.entrySet().stream()
                .map(entry -> {
                    String[] parts = entry.getKey().split(":", 2); // ["BINANCE", "BTCUSDT"]
                    return TrackedSymbol.builder()
                            .exchange(parts[0])
                            .symbol(parts[1])
                            .subscribers(entry.getValue().currentSubscriberCount())
                            .build();
                })
                .toList();
    }

    /** Exposes the raw broadcast flux — used internally by AlertEngine */
    public Flux<Asset> broadcastStream() {
        return broadcastSink.asFlux();
    }
    private String buildKey(String exchange, String symbol) {
        return exchange.toUpperCase() + ":" + symbol.toUpperCase();
    }
}
