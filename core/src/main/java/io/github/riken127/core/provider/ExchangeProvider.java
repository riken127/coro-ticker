package io.github.riken127.core.provider;

import java.util.List;

public interface ExchangeProvider {
    String getName();
    void connect();
    void disconnect();
    boolean isConnected();
    List<String> trackedSymbols();
}
