# Performance measurement

## What is measured

`MatchingEngineBenchmarkTest` calls the matching-core order-placement method repeatedly and reports invocation throughput and p99 latency from `System.nanoTime()` samples. Run it with `./scripts/benchmark.ps1`. It deliberately bypasses the Disruptor, HTTP, Kafka, and React; it is a core microbenchmark and is not representative of full end-to-end UI latency. The UI's engine p99 gauge is measured from command publication through completion on the single matching event handler and is distinct from browser rendering and network latency.

The benchmark is a repeatable harness, not a pre-recorded performance claim. Run on a named machine/runtime, retain the console output, and repeat under a representative workload before comparing results. Warm-up, CPU scheduling, JVM version, GC, and machine power policy affect these numbers. The 100,000 orders/sec and p99 below 100 µs goals are targets only; this project does not claim that they have been met. GC-free operation and lock-free behavior across the whole app are not claimed.
