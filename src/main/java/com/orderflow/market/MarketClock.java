package com.orderflow.market;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

@Component
public class MarketClock extends Clock {
    private volatile Instant replayTime;

    @Override
    public ZoneId getZone() { return ZoneOffset.UTC; }

    @Override
    public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }

    @Override
    public Instant instant() {
        Instant replay = replayTime;
        return replay == null ? Instant.now() : replay;
    }

    void replayAt(Instant timestamp) { replayTime = timestamp; }
    void useWallClock() { replayTime = null; }
}
