package io.github.riken127.core.provider;

import io.github.riken127.core.service.MarketDataService;
import io.github.riken127.schemas.codegen.types.Asset;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

@Component
@Log4j2
public class BinanceProvider {
    private final MarketDataService marketDataService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BinanceProvider(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @PostConstruct
    public void init() {
        String url = "wss://stream.binance.com:9443/ws/btcusdt@aggTrade/ethusdt@aggTrade";

        HttpClient.create()
                .websocket()
                .uri(url)
                .handle((in, out) -> in.receive().asString())
                .map(this::mapToAsset)
                .doOnNext(marketDataService::submitUpdate)
                .doOnError(e -> log.error("Erro no stream da Binance", e))
                .retry() // Auto-reconnect se a net cair
                .subscribe();
    }

    private Asset mapToAsset(String rawJson) {
        try {
            JsonNode json = objectMapper.readTree(rawJson);
            return Asset.newBuilder()
                    .symbol(json.get("s").asText())
                    .currentPrice(json.get("p").asDouble())
                    .lastUpdated(Instant.now().toString())
                    // Hardcoded para o exemplo, em prod viria de outro stream
                    .name(json.get("s").asText().replace("USDT", ""))
                    .change24h(0.0)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Falha ao processar JSON", e);
        }
    }
}
