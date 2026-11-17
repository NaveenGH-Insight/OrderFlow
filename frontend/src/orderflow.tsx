import { useEffect, useMemo, useRef, useState } from "react";

type Level = { priceTicks: number; quantity: number; orderCount: number };
type Trade = { id: string; priceTicks: number; quantity: number; aggressor: "BUY" | "SELL"; timestamp: string };
type Candle = { openTimeMillis: number; openTicks: number; highTicks: number; lowTicks: number; closeTicks: number; volume: number };
type Snapshot = {
  symbol: string; sequence: number; lastPriceTicks: number; bestBidTicks: number; bestAskTicks: number;
  bids: Level[]; asks: Level[]; trades: Trade[]; candles: Candle[];
  availableCashTicks: number; availableShares: number; ordersPerSecond: number; p99LatencyMicros: number;
};
type OrderResult = { orderId: string; status: string; filledQuantity: number; remainingQuantity: number; averagePriceTicks: number; message: string };

const money = (ticks: number) => `$${(ticks / 100).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
const number = (value: number) => new Intl.NumberFormat("en-US").format(value);

function Chart({ snapshot, mode }: { snapshot: Snapshot | null; mode: "price" | "depth" }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const canvas = ref.current;
    const context = canvas?.getContext("2d");
    if (!canvas || !context || !snapshot) return;
    const bounds = canvas.getBoundingClientRect();
    const ratio = window.devicePixelRatio || 1;
    canvas.width = Math.max(1, Math.floor(bounds.width * ratio));
    canvas.height = Math.max(1, Math.floor(bounds.height * ratio));
    context.scale(ratio, ratio);
    const width = bounds.width;
    const height = bounds.height;
    context.clearRect(0, 0, width, height);
    context.strokeStyle = "#202a36";
    context.lineWidth = 1;
    for (let i = 1; i < 5; i++) {
      const y = (height * i) / 5;
      context.beginPath(); context.moveTo(0, y); context.lineTo(width, y); context.stroke();
    }

    if (mode === "depth") {
      const bidQty = snapshot.bids.reduce((sum, level) => sum + level.quantity, 0) || 1;
      const askQty = snapshot.asks.reduce((sum, level) => sum + level.quantity, 0) || 1;
      const drawSide = (levels: Level[], color: string, total: number, reverse: boolean) => {
        const sorted = reverse ? [...levels].reverse() : [...levels];
        let cumulative = 0;
        context.beginPath();
        context.moveTo(0, height);
        sorted.forEach((level, index) => {
          cumulative += level.quantity;
          const x = (width * index) / Math.max(1, sorted.length - 1);
          const y = height - (cumulative / total) * height * 0.85;
          context.lineTo(x, y);
        });
        context.lineTo(width, height);
        context.closePath();
        context.fillStyle = `${color}18`;
        context.fill();
        context.strokeStyle = color;
        context.lineWidth = 2;
        context.stroke();
      };
      drawSide(snapshot.bids, "#48c99a", bidQty, true);
      drawSide(snapshot.asks, "#ee6f79", askQty, false);
      return;
    }

    const candles = snapshot.candles.slice(-48);
    if (!candles.length) {
      context.fillStyle = "#8895a4";
      context.font = "12px Inter, sans-serif";
      context.fillText("Waiting for the first simulated match…", 16, 28);
      return;
    }
    const low = Math.min(...candles.map((candle) => candle.lowTicks));
    const high = Math.max(...candles.map((candle) => candle.highTicks));
    const range = Math.max(1, high - low);
    const slot = width / candles.length;
    const yFor = (price: number) => height - ((price - low) / range) * (height * 0.78) - 12;
    candles.forEach((candle, index) => {
      const x = index * slot + slot / 2;
      const rising = candle.closeTicks >= candle.openTicks;
      const color = rising ? "#48c99a" : "#ee6f79";
      context.strokeStyle = color;
      context.fillStyle = color;
      context.beginPath(); context.moveTo(x, yFor(candle.highTicks)); context.lineTo(x, yFor(candle.lowTicks)); context.stroke();
      const top = Math.min(yFor(candle.openTicks), yFor(candle.closeTicks));
      const body = Math.max(2, Math.abs(yFor(candle.openTicks) - yFor(candle.closeTicks)));
      context.fillRect(x - Math.max(2, slot * 0.28), top, Math.max(3, slot * 0.56), body);
    });
    context.fillStyle = "#94a2b1";
    context.font = "11px Inter, sans-serif";
    context.fillText(money(high), width - 72, 14);
    context.fillText(money(low), width - 72, height - 5);
  }, [snapshot, mode]);
  return <canvas className="market-chart" ref={ref} aria-label={`${mode} chart`} />;
}

function DepthRows({ levels, side, maximum }: { levels: Level[]; side: "bid" | "ask"; maximum: number }) {
  const visible = side === "bid" ? [...levels].reverse() : [...levels].reverse();
  return <div className="book-rows">{visible.map((level) => <div className={`book-row ${side}`} key={level.priceTicks}>
    <span className="depth-bar" style={{ width: `${Math.min(100, (level.quantity / maximum) * 100)}%` }} />
    <span className="price">{money(level.priceTicks)}</span>
    <span>{number(level.quantity)}</span>
    <span className="muted">{level.orderCount}</span>
  </div>)}</div>;
}

export default function App() {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [connection, setConnection] = useState<"connecting" | "live" | "retrying">("connecting");
  const [side, setSide] = useState<"BUY" | "SELL">("BUY");
  const [type, setType] = useState<"LIMIT" | "MARKET">("LIMIT");
  const [price, setPrice] = useState("99.95");
  const [quantity, setQuantity] = useState("10");
  const [view, setView] = useState<"price" | "depth">("price");
  const [notice, setNotice] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [restingOrder, setRestingOrder] = useState<OrderResult | null>(null);
  const [shuttingDown, setShuttingDown] = useState(false);

  useEffect(() => {
    fetch("/api/market/snapshot").then((response) => {
      if (!response.ok) throw new Error("Initial market snapshot failed.");
      return response.json();
    }).then(setSnapshot).catch(() => setConnection("retrying"));
    const stream = new EventSource("/api/market/stream");
    stream.onopen = () => setConnection("live");
    stream.onmessage = (event) => {
      setSnapshot(JSON.parse(event.data) as Snapshot);
      setConnection("live");
    };
    stream.onerror = () => setConnection("retrying");
    return () => stream.close();
  }, []);

  const maxDepth = useMemo(() => Math.max(1, ...(snapshot?.bids ?? []).map((level) => level.quantity), ...(snapshot?.asks ?? []).map((level) => level.quantity)), [snapshot]);
  const spread = snapshot && snapshot.bestAskTicks && snapshot.bestBidTicks ? snapshot.bestAskTicks - snapshot.bestBidTicks : 0;

  async function submitOrder() {
    setSubmitting(true); setNotice("");
    try {
      const response = await fetch("/api/orders", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          symbol: "ORDF", accountId: "student", side, type,
          priceTicks: type === "LIMIT" ? Math.round(Number(price) * 100) : 0,
          quantity: Number(quantity)
        })
      });
      const payload = await response.json() as OrderResult | { detail?: string };
      if (!response.ok) throw new Error("detail" in payload ? payload.detail : "Order was rejected.");
      const result = payload as OrderResult;
      setRestingOrder(result.remainingQuantity > 0 && result.status !== "CANCELLED" ? result : null);
      setNotice(`${result.status.replaceAll("_", " ")} · ${number(result.filledQuantity)} filled${result.averagePriceTicks ? ` at ${money(result.averagePriceTicks)}` : ""}. ${result.message}`);
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Order could not be submitted.");
    } finally { setSubmitting(false); }
  }

  async function cancelOrder() {
    if (!restingOrder) return;
    try {
      const response = await fetch(`/api/orders/${restingOrder.orderId}/cancel?accountId=student`, { method: "POST" });
      const result = await response.json() as OrderResult;
      if (!response.ok) { setNotice("Order could not be cancelled."); return; }
      setNotice(`Order cancelled · ${number(result.remainingQuantity)} shares released.`);
      setRestingOrder(null);
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Order could not be cancelled.");
    }
  }

  async function quitApplication() {
    if (!window.confirm("Stop the local OrderFlow simulation?")) return;
    setShuttingDown(true);
    try {
      const response = await fetch("/api/shutdown", { method: "POST", headers: { "X-OrderFlow-Action": "quit" } });
      if (!response.ok) throw new Error("The shutdown request was rejected.");
      setNotice("OrderFlow is stopping. You can close this browser tab.");
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "The OrderFlow process could not be stopped from this tab.");
      setShuttingDown(false);
    }
  }

  const priceTicks = snapshot?.lastPriceTicks ?? 10_000;
  const sessionMove = ((priceTicks - 10_000) / 10_000) * 100;
  return <main className="app-shell">
    <header className="topbar">
      <a className="brand" href="#" aria-label="OrderFlow home"><span className="brand-mark">O</span><span>orderflow<span className="brand-light">.terminal</span></span></a>
      <div className="market-pill"><span className="pulse-dot" /> SIMULATED MARKET <span className="pill-divider">/</span> EDUCATIONAL</div>
      <div className={`connection ${connection}`}><span className="connection-dot" />{connection === "live" ? "LIVE DATA" : connection === "retrying" ? "RECONNECTING" : "CONNECTING"}</div>
      <button className="account-chip" title="Pre-funded educational account">NG <span>Student account</span><i>⌄</i></button>
      <button className="quit-button" disabled={shuttingDown} onClick={quitApplication}>{shuttingDown ? "Stopping…" : "Quit"}</button>
    </header>

    <section className="instrument">
      <div className="instrument-title">
        <div className="ticker-icon">OF</div>
        <div><h1>OrderFlow Index <span className="symbol-badge">ORDF</span></h1><p>Simulated educational instrument <span className="dot-separator">•</span> USD</p></div>
      </div>
      <div className="last-price"><strong>{money(priceTicks)}</strong><span className={sessionMove >= 0 ? "positive" : "negative"}>{sessionMove >= 0 ? "+" : ""}{sessionMove.toFixed(2)}% <small>vs. demo open</small></span></div>
      <div className="instrument-stats">
        <div><span>BEST BID</span><b className="positive">{snapshot?.bestBidTicks ? money(snapshot.bestBidTicks) : "—"}</b></div>
        <div><span>BEST ASK</span><b className="negative">{snapshot?.bestAskTicks ? money(snapshot.bestAskTicks) : "—"}</b></div>
        <div><span>SPREAD</span><b>{spread ? money(spread) : "—"}</b></div>
        <div><span>ENGINE P99</span><b>{snapshot?.p99LatencyMicros ?? 0}<small> µs</small></b></div>
      </div>
    </section>

    <div className="dashboard-grid">
      <section className="panel chart-panel">
        <div className="panel-heading">
          <div><h2>Market overview</h2><p>Live price action &amp; liquidity</p></div>
          <div className="segmented"><button className={view === "price" ? "selected" : ""} onClick={() => setView("price")}>Price</button><button className={view === "depth" ? "selected" : ""} onClick={() => setView("depth")}>Depth</button></div>
        </div>
        <div className="chart-meta"><span><i className="legend-dot green" /> Trades / 1 min</span><span className="muted">Depth refresh · 100 ms</span></div>
        <Chart snapshot={snapshot} mode={view} />
        <div className="chart-footer"><span>Market orders are simulated against local demo liquidity.</span><span>{number(snapshot?.sequence ?? 0)} events</span></div>
      </section>

      <section className="panel order-panel">
        <div className="panel-heading"><div><h2>Place order</h2><p>Funds are simulated; no real trades.</p></div><span className="demo-tag">DEMO</span></div>
        <div className="side-toggle"><button className={side === "BUY" ? "buy selected" : "buy"} onClick={() => setSide("BUY")}>Buy</button><button className={side === "SELL" ? "sell selected" : "sell"} onClick={() => setSide("SELL")}>Sell</button></div>
        <div className="field-label">ORDER TYPE</div>
        <div className="segmented full"><button className={type === "LIMIT" ? "selected" : ""} onClick={() => setType("LIMIT")}>Limit</button><button className={type === "MARKET" ? "selected" : ""} onClick={() => setType("MARKET")}>Market</button></div>
        {type === "LIMIT" && <label className="field-label input-label">LIMIT PRICE <span>USD</span>
          <div className="input-wrap"><span>$</span><input type="number" min="0.01" step="0.01" value={price} onChange={(event) => setPrice(event.target.value)} aria-label="Limit price" /></div>
        </label>}
        <label className="field-label input-label">QUANTITY <span>SHARES</span>
          <div className="input-wrap"><input type="number" min="1" step="1" value={quantity} onChange={(event) => setQuantity(event.target.value)} aria-label="Quantity" /><span className="unit">ORDF</span></div>
        </label>
        <div className="order-estimate"><span>Est. order value</span><b>{money((type === "LIMIT" ? Math.round(Number(price) * 100) : priceTicks) * (Number(quantity) || 0))}</b></div>
        <button className={`submit-order ${side.toLowerCase()}`} disabled={submitting} onClick={submitOrder}>{submitting ? "Sending…" : `${side === "BUY" ? "Buy" : "Sell"} ORDF`}</button>
        {notice && <div className="notice" role="status">{notice}{restingOrder && <button onClick={cancelOrder}>Cancel open</button>}</div>}
        <div className="risk-note"><span>ⓘ</span><p>Pre-trade checks reserve simulated cash or shares. Market orders have a 5% price protection band.</p></div>
        <div className="buying-power"><span>Available buying power</span><b>{money(snapshot?.availableCashTicks ?? 0)}</b></div>
      </section>

      <section className="panel book-panel">
        <div className="panel-heading compact"><div><h2>Order book</h2><p>Aggregated depth <span className="live-refresh">● 100ms</span></p></div><span className="book-columns">QTY <span>ORDERS</span></span></div>
        <div className="book-header"><span>PRICE <small>USD</small></span><span>QUANTITY</span><span>ORDERS</span></div>
        <DepthRows levels={snapshot?.asks ?? []} side="ask" maximum={maxDepth} />
        <div className="mid-price"><b>{money(priceTicks)}</b><span>LAST TRADE</span><i>{snapshot?.bestAskTicks && snapshot.bestBidTicks ? money(snapshot.bestAskTicks - snapshot.bestBidTicks) : "—"} spread</i></div>
        <DepthRows levels={snapshot?.bids ?? []} side="bid" maximum={maxDepth} />
      </section>

      <section className="panel trades-panel">
        <div className="panel-heading compact"><div><h2>Recent trades</h2><p>Matched execution tape</p></div><span className="live-tag"><i /> STREAMING</span></div>
        <div className="trades-header"><span>TIME</span><span>PRICE</span><span>SIZE</span><span>SIDE</span></div>
        <div className="trade-list">{(snapshot?.trades ?? []).slice(0, 10).map((trade) => <div className="trade-row" key={trade.id}>
          <span>{new Date(trade.timestamp).toLocaleTimeString("en-US", { hour12: false })}</span>
          <b className={trade.aggressor === "BUY" ? "positive" : "negative"}>{money(trade.priceTicks)}</b>
          <span>{number(trade.quantity)}</span><span className={trade.aggressor === "BUY" ? "trade-side buy" : "trade-side sell"}>{trade.aggressor}</span>
        </div>)}
          {!snapshot?.trades.length && <div className="empty-state">Demo prints will appear here shortly.</div>}
        </div>
      </section>
    </div>

    <footer><span><b>OrderFlow</b> <span className="muted">· College project simulation</span></span><span><b>{snapshot?.ordersPerSecond ?? 0}</b> engine req/s <span className="footer-divider">|</span> p99 <b>{snapshot?.p99LatencyMicros ?? 0} µs</b></span><span>Not connected to any exchange or real account.</span></footer>
  </main>;
}
