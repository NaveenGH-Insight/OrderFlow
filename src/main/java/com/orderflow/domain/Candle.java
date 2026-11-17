package com.orderflow.domain;

public record Candle(long openTimeMillis, long openTicks, long highTicks,
                     long lowTicks, long closeTicks, long volume) {}
