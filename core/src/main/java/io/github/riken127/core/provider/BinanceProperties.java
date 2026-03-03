package io.github.riken127.core.provider;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "binance")
@Data
public class BinanceProperties {
    private List<String> symbols = List.of(
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT"
    );
}
