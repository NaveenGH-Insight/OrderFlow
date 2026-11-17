package com.orderflow.engine;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

final class EngineMetrics {
    private static final int WINDOW = 4096;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong[] latency = new AtomicLong[WINDOW];
    private final LongAdder completed = new LongAdder();
    private volatile long rateWindowStart = System.nanoTime();
    private volatile long completedInWindow;
    private volatile long ordersPerSecond;

    EngineMetrics() {
        Arrays.setAll(latency, ignored -> new AtomicLong());
    }

    void record(long startedNanos) {
        long elapsed = System.nanoTime() - startedNanos;
        latency[(int) (sequence.getAndIncrement() & (WINDOW - 1))].set(elapsed);
        completed.increment();
        long now = System.nanoTime();
        if (now - rateWindowStart >= 1_000_000_000L) {
            long total = completed.sum();
            ordersPerSecond = total - completedInWindow;
            completedInWindow = total;
            rateWindowStart = now;
        }
    }

    long ordersPerSecond() { return ordersPerSecond; }

    long p99Micros() {
        int count = (int) Math.min(sequence.get(), WINDOW);
        if (count == 0) return 0;
        long[] samples = new long[count];
        long end = sequence.get();
        for (int i = 0; i < count; i++) samples[i] = latency[(int) ((end - count + i) & (WINDOW - 1))].get();
        Arrays.sort(samples);
        return samples[(int) Math.ceil(count * 0.99) - 1] / 1_000;
    }
}
