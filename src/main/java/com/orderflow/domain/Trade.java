package com.orderflow.domain;

import java.time.Instant;

public record Trade(String id, String symbol, long priceTicks, long quantity,
                    String aggressor, Instant timestamp) {}
