package io.github.riken127.core.provider;

import io.github.riken127.core.domain.Asset;
import io.github.riken127.core.engine.MarketDataEngine;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.WebsocketClientSpec;
import reactor.util.retry.Retry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@Log4j2
public class BinanceProvider implements ExchangeProvider{
    private static final String EXCHANGE       = "BINANCE";
    private static final String WS_BASE        = "wss://stream.binance.com:9443/stream?streams=";
    private static final Duration INIT_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF  = Duration.ofSeconds(60);

    private final MarketDataEngine engine;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final BinanceProperties properties;

    private final AtomicBoolean connected = new AtomicBoolean(false);

    private final AtomicBoolean metricsRegistered = new AtomicBoolean(false);

    // Holds partial state per symbol while we wait for both stream types to arrive
    private final ConcurrentHashMap<String, AssetAccumulator> accumulators =
            new ConcurrentHashMap<>();

    private volatile Disposable subscription;

    public BinanceProvider(MarketDataEngine engine,
                           ObjectMapper objectMapper,
                           MeterRegistry meterRegistry,
                           BinanceProperties properties) {
        this.engine      = engine;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.properties  = properties;
    }

    @Override
    public String getName() {
        return EXCHANGE;
    }

    @PostConstruct
    @Override
    public void connect() {
        String streamPath = properties.getSymbols().stream()
                .flatMap(s -> Stream.of(
                        s.toLowerCase() + "@aggTrade",
                        s.toLowerCase() + "@miniTicker"   // brings high24h, low24h, volume24h, change
                ))
                .collect(Collectors.joining("/"));

        subscription = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .websocket(WebsocketClientSpec.builder()
                        .maxFramePayloadLength(65_536)
                        .build())
                .uri(WS_BASE + streamPath)
                .handle((inbound, outbound) -> {
                    connected.set(true);
                    log.info("[Binance] Connected — streams: {}", streamPath);
                    meterRegistry.gauge("binance.ws.connected", 1);

                    return inbound.receive()
                            .asString(StandardCharsets.UTF_8)
                            .flatMap(this::processFrame, 64) // 64 in-flight parses max
                            .doOnNext(engine::submitUpdate)
                            .then();
                })
                .doOnTerminate(() -> {
                    connected.set(false);
                    meterRegistry.gauge("binance.ws.connected", 0);
                    log.warn("[Binance] WebSocket terminated");
                })
                .retryWhen(Retry.backoff(Long.MAX_VALUE, INIT_BACKOFF)
                        .maxBackoff(MAX_BACKOFF)
                        .jitter(0.3)
                        .doBeforeRetry(signal -> {
                            meterRegistry.counter("binance.ws.reconnects").increment();
                            log.warn("[Binance] Reconnecting (attempt {})",
                                    signal.totalRetries() + 1);
                            // Clear accumulators on reconnect — stale partial state is worse than empty
                            accumulators.clear();
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        null,
                        err -> log.error("[Binance] Non-recoverable error", err)
                );
    }

    @PreDestroy
    @Override
    public void disconnect() {
        if (metricsRegistered.compareAndSet(false, true)) {
            meterRegistry.gauge("binance.ws.connected", connected, b -> b.get() ? 1 : 0);
        }
        if (subscription != null && !subscription.isDisposed()) {
            log.info("[Binance] Disconnecting");
            subscription.dispose();
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public List<String> trackedSymbols() {
        return properties.getSymbols();
    }

    /**
     * Binance combined stream wraps each frame as:
     * { "stream": "btcusdt@aggTrade", "data": { ... } }
     * <p>
     * We parse the stream name to decide which handler to call,
     * then return a Mono<Asset> only when the accumulator has enough data.
     */
    private Mono<Asset> processFrame(String rawJson) {
        return Mono.fromCallable(() -> {
                    JsonNode root = objectMapper.readTree(rawJson);
                    String stream = root.path("stream").asText("");
                    JsonNode data = root.path("data");

                    if (stream.endsWith("@aggTrade")) {
                        return handleAggTrade(data);
                    } else if (stream.endsWith("@miniTicker")) {
                        return handleMiniTicker(data);
                    } else {
                        log.debug("[Binance] Unknown stream frame, skipping: {}", stream);
                        return null;
                    }
                })
                .onErrorResume(e -> {
                    meterRegistry.counter("binance.parse.errors").increment();
                    log.warn("[Binance] Skipping unparseable frame: {}", rawJson, e);
                    return Mono.empty();
                })
                .filter(asset -> asset != null); // accumulator returns null until both halves arrived
    }

    /**
     * aggTrade fields used:
     *   s = symbol, p = price
     */
    private Asset handleAggTrade(JsonNode data) {
        String symbol = data.get("s").asText().toUpperCase();
        BigDecimal price = new BigDecimal(data.get("p").asText());

        return accumulators
                .computeIfAbsent(symbol, AssetAccumulator::new)
                .applyTrade(price)
                .tryBuild(EXCHANGE);
    }

    /**
     * miniTicker fields used:
     *   s = symbol, h = high24h, l = low24h, v = volume24h,
     *   p = priceChange (absolute), P = priceChangePercent
     */
    private Asset handleMiniTicker(JsonNode data) {
        String symbol     = data.get("s").asText().toUpperCase();
        BigDecimal close  = new BigDecimal(data.get("c").asText());
        BigDecimal open   = new BigDecimal(data.get("o").asText());
        BigDecimal high   = new BigDecimal(data.get("h").asText());
        BigDecimal low    = new BigDecimal(data.get("l").asText());
        BigDecimal volume = new BigDecimal(data.get("v").asText());

        // Derive what miniTicker doesn't provide directly
        BigDecimal change    = close.subtract(open);
        BigDecimal changePct = open.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : change.divide(open, 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        return accumulators
                .computeIfAbsent(symbol, AssetAccumulator::new)
                .applyTicker(high, low, volume, change, changePct)
                .tryBuild(EXCHANGE);
    }
}
