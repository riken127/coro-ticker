package io.github.riken127.core.service;

import io.github.riken127.schemas.codegen.types.Asset;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Service
public class MarketDataService {
    private final Sinks.Many<Asset> marketSink = Sinks.many()
            .multicast()
            .directBestEffort();

    public void submitUpdate(Asset asset) {
        marketSink.tryEmitNext(asset);
    }

    public Flux<Asset> getPriceStream() {
        return marketSink.asFlux();
    }
}
