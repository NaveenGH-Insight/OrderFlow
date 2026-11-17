import { useEffect, useMemo, useState } from "react";
import MarketChart from "./MarketChart";
import { money, number } from "./market";
import type { Level, Snapshot } from "./market";
type OrderResult = { orderId: string; status: string; filledQuantity: number; remainingQuantity: number; averagePriceTicks: number; message: string };

function DepthRows({ levels, side, maximum }: { levels: Level[]; side: "bid" | "ask"; maximum: number }) {
  const visible = side === "ask" ? levels.slice(0, 5).reverse() : levels.slice(0, 5);
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
  const [price, setPrice] = useState("");
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
      const state = JSON.parse(event.data) as Snapshot;
      setSnapshot(state);
      setPrice(current => current || (state.bestAskTicks / 100).toFixed(2));
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
      setRestingOrder(result.remainingQuantity > 0 && type === "LIMIT"
        && (result.status === "RESTING" || result.status === "PARTIALLY_FILLED") ? result : null);
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

  const priceTicks = snapshot?.lastPriceTicks ?? 18_640;
  const openingPrice = snapshot?.candles[0]?.openTicks ?? priceTicks;
  const sessionMove = ((priceTicks - openingPrice) / openingPrice) * 100;
  const sessionHigh = Math.max(priceTicks, ...(snapshot?.candles ?? []).map(candle => candle.highTicks));
  const sessionLow = Math.min(priceTicks, ...(snapshot?.candles ?? []).map(candle => candle.lowTicks));
  const volume = (snapshot?.candles ?? []).reduce((sum, candle) => sum + candle.volume, 0);
  return <main className="app-shell">
    <header className="topbar">
      <a className="brand" href="#" aria-label="OrderFlow home"><span className="brand-mark">O</span><span>orderflow<span className="brand-light">.terminal</span></span></a>
      <nav className="terminal-nav"><span className="active">Markets</span><span>ORDF / USD</span></nav>
      <div className={`connection ${connection}`}><span className="connection-dot" />{connection === "live" ? "CONNECTED" : connection === "retrying" ? "RECONNECTING" : "CONNECTING"}</div>
      <span className="account-chip" title="Pre-funded practice account">OF <span>Practice portfolio</span></span>
      <button className="quit-button" disabled={shuttingDown} onClick={quitApplication}>{shuttingDown ? "Stopping…" : "Quit"}</button>
    </header>

    <section className="instrument">
      <div className="instrument-title">
        <div className="ticker-icon">OF</div>
        <div><h1>OrderFlow Index <span className="symbol-badge">ORDF</span></h1><p>Equities <span className="dot-separator">/</span> ORDF · USD</p></div>
      </div>
      <div className="last-price"><strong data-testid="last-price">{money(priceTicks)}</strong><span className={sessionMove >= 0 ? "positive" : "negative"}>{sessionMove >= 0 ? "+" : ""}{sessionMove.toFixed(2)}% <small>session</small></span></div>
      <div className="instrument-stats">
        <div><span>SESSION HIGH</span><b>{money(sessionHigh)}</b></div>
        <div><span>SESSION LOW</span><b>{money(sessionLow)}</b></div>
        <div><span>VOLUME</span><b>{number(volume)}</b></div>
        <div><span>SPREAD</span><b>{spread ? money(spread) : "—"}</b></div>
      </div>
    </section>

    <div className="dashboard-grid">
      <section className="panel chart-panel">
        <div className="panel-heading">
          <div><h2>Price chart <span className="chart-symbol">ORDF</span></h2><p>Follow the price. Read the momentum.</p></div>
          <div className="segmented"><button className={view === "price" ? "selected" : ""} onClick={() => setView("price")}>Candles</button><button className={view === "depth" ? "selected" : ""} onClick={() => setView("depth")}>Depth</button></div>
        </div>
        <MarketChart snapshot={snapshot} mode={view} />
        <div className="chart-footer"><span><i className="legend-dot green" /> Execution-based OHLC</span><span>Local time · USD</span></div>
      </section>

      <section className="panel order-panel">
        <div className="panel-heading"><div><h2>Trade ORDF</h2><p>Your next move, in focus.</p></div><span className="order-symbol">ORDF</span></div>
        <div className="side-toggle"><button className={side === "BUY" ? "buy selected" : "buy"} onClick={() => setSide("BUY")}>Buy</button><button className={side === "SELL" ? "sell selected" : "sell"} onClick={() => setSide("SELL")}>Sell</button></div>
        <div className="field-label">ORDER TYPE</div>
        <div className="segmented full"><button className={type === "LIMIT" ? "selected" : ""} onClick={() => setType("LIMIT")}>Limit</button><button className={type === "MARKET" ? "selected" : ""} onClick={() => setType("MARKET")}>Market</button></div>
        {type === "LIMIT" && <label className="field-label input-label">LIMIT PRICE <span>USD</span>
          <div className="input-wrap"><span>$</span><input type="number" min="0.01" step="0.01" value={price} onChange={(event) => setPrice(event.target.value)} aria-label="Limit price" /></div>
          <button className="use-market" onClick={() => setPrice((((side === "BUY" ? snapshot?.bestAskTicks : snapshot?.bestBidTicks) ?? priceTicks) / 100).toFixed(2))}>Use best {side === "BUY" ? "ask" : "bid"}</button>
        </label>}
        <label className="field-label input-label">QUANTITY <span>SHARES</span>
          <div className="input-wrap"><input type="number" min="1" step="1" value={quantity} onChange={(event) => setQuantity(event.target.value)} aria-label="Quantity" /><span className="unit">ORDF</span></div>
        </label>
        <div className="order-estimate"><span>Est. order value</span><b>{money((type === "LIMIT" ? Math.round(Number(price) * 100) : priceTicks) * (Number(quantity) || 0))}</b></div>
        <button className={`submit-order ${side.toLowerCase()}`} disabled={submitting} onClick={submitOrder}>{submitting ? "Sending…" : `${side === "BUY" ? "Buy" : "Sell"} ORDF`}</button>
        {notice && <div className="notice" role="status">{notice}{restingOrder && <button onClick={cancelOrder}>Cancel open</button>}</div>}
        <div className="risk-note"><span>↗</span><p>Market orders include 5% price protection. Limit orders execute at your price or better.</p></div>
        <div className="buying-power"><span>Available buying power</span><b>{money(snapshot?.availableCashTicks ?? 0)}</b></div>
        <div className="buying-power holdings"><span>Shares available</span><b>{number(snapshot?.availableShares ?? 0)} ORDF</b></div>
        <div className="portfolio-note"><div className="portfolio-icon">◎</div><h3>Built for your next move</h3><p>Watch the spread, compare liquidity, and place a considered order.</p></div>
      </section>

      <section className="panel book-panel">
        <div className="panel-heading compact"><div><h2>Market depth</h2><p>Price-time priority <span className="live-refresh">● 100ms</span></p></div><span className="book-columns">TOP 5 LEVELS</span></div>
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
          {!snapshot?.trades.length && <div className="empty-state">Waiting for executions.</div>}
        </div>
      </section>
    </div>

    <footer><span><b>OrderFlow</b><span className="footer-divider">/</span>Simulated prices &amp; funds. No exchange connection or real trades.</span><span><b>{snapshot?.ordersPerSecond ?? 0}</b> engine req/s <span className="footer-divider">|</span> p99 <b>{snapshot?.p99LatencyMicros ?? 0} µs</b></span></footer>
  </main>;
}
