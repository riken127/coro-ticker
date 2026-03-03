package io.github.riken127.app.resolver;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsSubscription;
import com.netflix.graphql.dgs.InputArgument;
import io.github.riken127.core.domain.Asset;
import io.github.riken127.core.engine.MarketDataEngine;
import lombok.extern.log4j.Log4j2;
import org.reactivestreams.Publisher;

import java.util.List;

@DgsComponent
@Log4j2
public class TickerDataFetcher {

    private final MarketDataEngine marketDataEngine;

    public TickerDataFetcher(MarketDataEngine marketDataEngine) {
        this.marketDataEngine = marketDataEngine;
    }

    @DgsSubscription
    public Publisher<Asset> marketTicks(
            @InputArgument List<String> symbols,
            @InputArgument String exchange) {

        String resolvedExchange = exchange != null ? exchange : "BINANCE";

        return marketDataEngine.streamSymbols(resolvedExchange, symbols)
                .doOnSubscribe(s -> log.info("[WS] Client subscribed — exchange={} symbols={}",
                        resolvedExchange,
                        symbols == null || symbols.isEmpty() ? "ALL" : symbols))
                .doOnCancel(() -> log.info("[WS] Client disconnected — exchange={} symbols={}",
                        resolvedExchange, symbols));
    }
}
