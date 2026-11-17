import { useEffect, useMemo, useRef, useState } from "react";
import { aggregateCandles, money, number, priceRange } from "./market";
import type { Snapshot } from "./market";

const time = (timestamp: number) => new Date(timestamp).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });
const GREEN = "#24c9a0";
const RED = "#f57887";

export default function MarketChart({ snapshot, mode }: { snapshot: Snapshot | null; mode: "price" | "depth" }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [minutes, setMinutes] = useState(1);
  const [hover, setHover] = useState<number | null>(null);
  const [size, setSize] = useState({ width: 0, height: 0 });
  const candles = useMemo(() => aggregateCandles(snapshot?.candles ?? [], minutes).slice(-65), [snapshot?.candles, minutes]);
  const selected = candles[hover ?? candles.length - 1];

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const observer = new ResizeObserver(entries => {
      const { width, height } = entries[0].contentRect;
      setSize({ width, height });
    });
    observer.observe(canvas);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext("2d");
    if (!canvas || !ctx || !snapshot || !size.width) return;
    const { width, height } = size;
    const ratio = window.devicePixelRatio || 1;
    canvas.width = Math.round(width * ratio);
    canvas.height = Math.round(height * ratio);
    ctx.scale(ratio, ratio);
    ctx.clearRect(0, 0, width, height);
    const left = 10;
    const right = width - 72;
    const top = 14;
    const bottom = height - 27;
    const priceBottom = bottom - 58;
    const chartWidth = right - left;
    ctx.font = "10px Consolas, monospace";

    if (mode === "depth") {
      const levels = [...snapshot.bids, ...snapshot.asks];
      if (!levels.length) return;
      const low = Math.min(...levels.map(level => level.priceTicks)) - 2;
      const high = Math.max(...levels.map(level => level.priceTicks)) + 2;
      const maxQty = Math.max(1, snapshot.bids.reduce((sum, level) => sum + level.quantity, 0),
        snapshot.asks.reduce((sum, level) => sum + level.quantity, 0));
      const x = (price: number) => left + ((price - low) / (high - low)) * chartWidth;
      const y = (qty: number) => bottom - (qty / maxQty) * (bottom - top);
      for (let i = 0; i <= 4; i++) {
        const qty = maxQty * i / 4;
        ctx.strokeStyle = "#212b3c"; ctx.fillStyle = "#75849a";
        ctx.beginPath(); ctx.moveTo(left, y(qty)); ctx.lineTo(right, y(qty)); ctx.stroke();
        ctx.fillText(number(Math.round(qty)), right + 8, y(qty) + 3);
        const price = low + (high - low) * i / 4;
        ctx.fillText(money(price), x(price) - 17, height - 7);
      }
      for (const [side, color] of [[snapshot.bids, GREEN], [snapshot.asks, RED]] as const) {
        let total = 0;
        ctx.beginPath();
        side.forEach((level, index) => {
          if (!index) ctx.moveTo(x(level.priceTicks), bottom);
          total += level.quantity;
          ctx.lineTo(x(level.priceTicks), y(total));
        });
        if (side.length) ctx.lineTo(x(side[side.length - 1].priceTicks), bottom);
        ctx.closePath(); ctx.fillStyle = `${color}18`; ctx.fill();
        ctx.strokeStyle = color; ctx.lineWidth = 1.5; ctx.stroke();
      }
      return;
    }

    const range = priceRange(candles, snapshot.lastPriceTicks);
    const y = (price: number) => priceBottom - ((price - range.low) / (range.high - range.low)) * (priceBottom - top);
    const slots = Math.max(45, candles.length + 3);
    const slot = chartWidth / slots;
    const x = (index: number) => left + (index + 0.5) * slot;
    const bodyWidth = Math.max(2, Math.min(8, slot * 0.62));
    for (let i = 0; i <= 4; i++) {
      const price = range.low + (range.high - range.low) * i / 4;
      const yy = y(price);
      ctx.strokeStyle = "#202a3a"; ctx.lineWidth = 0.7;
      ctx.beginPath(); ctx.moveTo(left, yy); ctx.lineTo(right, yy); ctx.stroke();
      ctx.fillStyle = "#7f8ba0"; ctx.fillText((price / 100).toFixed(2), right + 10, yy + 3);
    }
    const maxVolume = Math.max(1, ...candles.map(candle => candle.volume));
    candles.forEach((candle, index) => {
      const color = candle.closeTicks >= candle.openTicks ? GREEN : RED;
      ctx.strokeStyle = color; ctx.fillStyle = color; ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(x(index), y(candle.highTicks)); ctx.lineTo(x(index), y(candle.lowTicks)); ctx.stroke();
      const bodyTop = Math.min(y(candle.openTicks), y(candle.closeTicks));
      const bodyHeight = Math.max(1.3, Math.abs(y(candle.openTicks) - y(candle.closeTicks)));
      ctx.fillRect(x(index) - bodyWidth / 2, bodyTop, bodyWidth, bodyHeight);
      ctx.fillStyle = `${color}55`;
      const volumeHeight = (candle.volume / maxVolume) * 40;
      ctx.fillRect(x(index) - bodyWidth / 2, bottom - volumeHeight, bodyWidth, volumeHeight);
      if (index % Math.max(1, Math.ceil(slots / 6)) === 0) {
        ctx.fillStyle = "#7f8ba0"; ctx.fillText(time(candle.openTimeMillis), x(index) - 13, height - 7);
      }
    });
    const priceY = y(snapshot.lastPriceTicks);
    const last = candles[candles.length - 1];
    const lastColor = last && last.closeTicks < last.openTicks ? RED : GREEN;
    ctx.setLineDash([3, 4]); ctx.strokeStyle = `${lastColor}88`;
    ctx.beginPath(); ctx.moveTo(left, priceY); ctx.lineTo(right, priceY); ctx.stroke();
    ctx.setLineDash([]); ctx.fillStyle = lastColor; ctx.fillRect(right + 1, priceY - 9, 69, 18);
    ctx.fillStyle = "#061310"; ctx.fillText((snapshot.lastPriceTicks / 100).toFixed(2), right + 10, priceY + 3);
    ctx.fillStyle = "#65738a"; ctx.fillText("VOL", left, bottom - 44);
    if (hover !== null && candles[hover]) {
      const candle = candles[hover];
      ctx.setLineDash([3, 3]); ctx.strokeStyle = "#8c9ab4";
      ctx.beginPath(); ctx.moveTo(x(hover), top); ctx.lineTo(x(hover), bottom); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(left, y(candle.closeTicks)); ctx.lineTo(right, y(candle.closeTicks)); ctx.stroke();
      ctx.setLineDash([]); ctx.fillStyle = "#29334b"; ctx.fillRect(x(hover) - 22, height - 21, 44, 18);
      ctx.fillStyle = "#e0e5f3"; ctx.fillText(time(candle.openTimeMillis), x(hover) - 15, height - 8);
    }
  }, [snapshot, candles, hover, mode, size]);

  return <div className="chart-body">
    <div className="chart-toolbar">
      <div className="timeframes" aria-label="Candle interval">
        {[1, 5, 15].map(value => <button key={value} className={minutes === value ? "active" : ""}
          disabled={mode !== "price"} onClick={() => { setMinutes(value); setHover(null); }}>{value}m</button>)}
      </div>
      <span>{mode === "price" ? "Candlesticks · Volume" : "Cumulative liquidity"}</span>
      <span className="chart-source">Matched executions</span>
    </div>
    <div className="ohlc-strip" aria-live="off">
      {selected && mode === "price" ? <>
        <span>O <b>{money(selected.openTicks)}</b></span><span>H <b>{money(selected.highTicks)}</b></span>
        <span>L <b>{money(selected.lowTicks)}</b></span><span>C <b className={selected.closeTicks >= selected.openTicks ? "positive" : "negative"}>{money(selected.closeTicks)}</b></span>
        <span className="ohlc-volume">VOL <b>{number(selected.volume)}</b></span>
      </> : <span>Bid and ask depth · aggregate quantity by price</span>}
    </div>
    <canvas className="market-chart" ref={canvasRef} aria-label={`${minutes}-minute ${mode === "price" ? "candlestick and volume" : "market depth"} chart`}
      data-candle-count={candles.length} data-interval={minutes}
      onMouseLeave={() => setHover(null)} onMouseMove={event => {
        const bounds = event.currentTarget.getBoundingClientRect();
        const slot = (bounds.width - 82) / Math.max(45, candles.length + 3);
        const index = Math.floor((event.clientX - bounds.left - 10) / slot);
        setHover(index >= 0 && index < candles.length ? index : null);
      }} />
  </div>;
}
