package io.github.riken127.app.resolver;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import io.github.riken127.core.domain.Asset;
import io.github.riken127.core.domain.TrackedSymbol;
import io.github.riken127.core.engine.MarketDataEngine;
import lombok.extern.log4j.Log4j2;

import java.util.List;

@DgsComponent
@Log4j2
public class MarketQueryResolver {

    private final MarketDataEngine marketDataEngine;

    public MarketQueryResolver(MarketDataEngine marketDataEngine) {
        this.marketDataEngine = marketDataEngine;
    }

    @DgsQuery
    public List<Asset> latestPrices(
            @InputArgument List<String> symbols,
            @InputArgument String exchange) {

        String resolvedExchange = exchange != null ? exchange : "BINANCE";
        List<Asset> result = marketDataEngine.latestPrices(resolvedExchange, symbols);

        log.debug("[Query] latestPrices — exchange={} requested={} returned={}",
                resolvedExchange,
                symbols == null ? "ALL" : symbols.size(),
                result.size());

        return result;
    }

    @DgsQuery
    public List<TrackedSymbol> trackedSymbols() {
        return marketDataEngine.trackedSymbols();
    }
}
