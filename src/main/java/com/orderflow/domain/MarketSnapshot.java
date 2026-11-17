package com.orderflow.domain;

import java.util.List;

public record MarketSnapshot(String symbol, long sequence, long lastPriceTicks,
                             long bestBidTicks, long bestAskTicks,
                             List<BookLevel> bids, List<BookLevel> asks,
                             List<Trade> trades, List<Candle> candles,
                             long availableCashTicks, long availableShares,
                             long ordersPerSecond, long p99LatencyMicros) {}
