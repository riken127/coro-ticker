package io.github.riken127.app.fetcher;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsSubscription;
import com.netflix.graphql.dgs.InputArgument;
import io.github.riken127.core.service.MarketDataService;
import io.github.riken127.schemas.codegen.types.Asset;
import org.reactivestreams.Publisher;

import java.util.List;

@DgsComponent
public class TickerDataFetcher {
    private final MarketDataService marketDataService;

    public TickerDataFetcher(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @DgsSubscription
    public Publisher<Asset> marketTicks(@InputArgument List<String> symbols) {
        return marketDataService.getPriceStream()
                .filter(asset -> symbols == null || symbols.isEmpty() ||
                        symbols.stream().anyMatch(s -> s.equalsIgnoreCase(asset.getSymbol())));
    }
}
