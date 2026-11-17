# OrderFlow

OrderFlow is a desktop-packaged educational trading simulation: a Java LMAX Disruptor intake feeds a single-writer price-time matching engine; a Spring WebFlux gateway streams 100 ms market snapshots; and a React terminal renders order-book depth, candles, recent trades, and simulated orders. It does not connect to a real exchange, brokerage, or real-money account.

## What is included

- Limit and protected-market buy/sell orders for the `ORDF` demo instrument.
- Price-time-priority matching, self-trade prevention, and order cancellation.
- Simulated pre-trade cash/share reservations, seeded demo liquidity, and a small activity simulator.
- SSE snapshots, aggregated Level 2 depth, recent executions, and one-minute candles.
- Optional Kafka trade-event producer for downstream consumers; local SSE works without Kafka.
- Canvas-based depth/candle visualization, accessible order-entry feedback, and a responsive dark terminal.
- Unit/protocol tests and a matching-core benchmark that reports measured throughput and p99 latency.
- A Windows MSI packaging script that uses `jpackage` and bundles its Java runtime.

All prices are integer cents per share (`priceTicks`); all funds are simulated cents. The default browser UI is bound to `127.0.0.1:8080`.

## Run from source

Prerequisites: JDK 21, Maven 3.9+, and Node.js 20+ with npm. From the repository root in PowerShell:

```powershell
Push-Location frontend
npm ci
npm test
npm run build
Pop-Location
mvn -B verify
java -jar target/orderflow.jar
```

The app opens `http://127.0.0.1:8080` in the default browser. Opening the packaged app again reopens the existing terminal rather than starting a conflicting backend. Set `ORDERFLOW_OPEN_BROWSER=false` or `--orderflow.open-browser=false` to suppress automatic browser opening on initial startup. Use the terminal's **Quit** button to stop the local service. `GET /api/health`, `GET /api/market/snapshot`, `GET /api/market/stream`, and `POST /api/orders` are the primary API routes. SSE is used for browser streaming; WebSocket is not required.

For frontend iteration, start the backend with `mvn spring-boot:run -Dspring-boot.run.arguments=--orderflow.open-browser=false`, then run `npm run dev` from `frontend`; Vite proxies `/api` to the loopback backend.

## Create the Windows installer

On Windows, install JDK 21 (with `jpackage`/`jlink`), Maven, Node.js/npm, and the WiX Toolset release supported by that JDK's `jpackage`. Then run:

```powershell
./scripts/package-msi.ps1
```

The script builds the UI and backend, runs Maven tests, and writes `dist/OrderFlow-1.0.1.msi`. `jpackage` bundles a Java runtime so an end user does not need Java or a developer toolchain. The workflow at `.github/workflows/build-windows.yml` performs the Windows build and publishes the MSI as a downloadable workflow artifact.

### Windows launch troubleshooting

Version 1.0.0 could leave its backend running without opening a browser because Spring used headless mode. Launching it again would then fail because port 8080 was occupied, and the native launcher displayed **Failed to launch JVM**. Version 1.0.1 enables desktop browsing and uses a per-user process lock to reopen the running terminal on subsequent launches. Quit the old application at `http://127.0.0.1:8080` before replacing it with the corrected installer. Startup/application logs are written to `%USERPROFILE%\.orderflow\logs\orderflow.log`. A different application using port 8080 must be closed or configured on another port.

The regression check `.\scripts\test-packaged-launch.ps1 -ApplicationDirectory <extracted-app-directory>` starts the actual bundled EXE, checks its HTTP health, frontend and seeded book, then verifies a second launcher exits successfully while preserving the original backend. It shuts the test app down afterward. This is a packaged-binary check, not clean-machine MSI installation verification.

The generated installer is unsigned unless signing is added in a controlled release process. The MSI build alone is not evidence that installation has been verified on a clean Windows machine. No built MSI or clean-machine install claim is included in source control.

## Tests and benchmark

```powershell
mvn -B verify
./scripts/benchmark.ps1
```

The benchmark exercises the matching core directly and prints the measured order count, throughput, and p99 invocation latency. It excludes risk reservation, the Disruptor queue, HTTP/SSE, Kafka, disk, and UI rendering. The terminal's rolling engine p99 measures command submission through matching-handler completion; neither number is a gateway/UI latency figure. The 100k orders/sec and sub-100 µs p99 figures are goals to measure and optimize, not asserted results. See [docs/architecture.md](docs/architecture.md) and [docs/performance.md](docs/performance.md).

## Optional Kafka trade stream

Kafka publishing is disabled by default. To enable it, configure `spring.kafka.bootstrap-servers` and set `orderflow.kafka.enabled=true` (for example, in a local `application.yml` outside version control). Matched trades are published as JSON to `orderflow.trades` by default; override the topic with `orderflow.kafka.topic`. Do not enable the producer without a reachable broker.

## Educational scope

Demo account balances are in-memory and reset on restart. The app has no authentication or persistence and is intentionally loopback-only; do not expose it to an untrusted network. This project is not an exchange, broker, investment product, or financial advice.

## License

MIT. See [LICENSE](LICENSE).
