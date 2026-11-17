export type Level = { priceTicks: number; quantity: number; orderCount: number };
export type Trade = { id: string; priceTicks: number; quantity: number; aggressor: "BUY" | "SELL"; timestamp: string };
export type Candle = { openTimeMillis: number; openTicks: number; highTicks: number; lowTicks: number; closeTicks: number; volume: number };
export type Snapshot = {
  symbol: string; sequence: number; lastPriceTicks: number; bestBidTicks: number; bestAskTicks: number;
  bids: Level[]; asks: Level[]; trades: Trade[]; candles: Candle[];
  availableCashTicks: number; availableShares: number; ordersPerSecond: number; p99LatencyMicros: number;
};

export const money = (ticks: number) => `$${(ticks / 100).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
export const number = (value: number) => new Intl.NumberFormat("en-US").format(value);

export function aggregateCandles(candles: Candle[], minutes: number): Candle[] {
  const buckets: Candle[] = [];
  for (const candle of candles) {
    const time = Math.floor(candle.openTimeMillis / (minutes * 60_000)) * minutes * 60_000;
    const current = buckets[buckets.length - 1];
    if (!current || current.openTimeMillis !== time) {
      buckets.push({ ...candle, openTimeMillis: time });
    } else {
      current.highTicks = Math.max(current.highTicks, candle.highTicks);
      current.lowTicks = Math.min(current.lowTicks, candle.lowTicks);
      current.closeTicks = candle.closeTicks;
      current.volume += candle.volume;
    }
  }
  return buckets;
}

export function priceRange(candles: Candle[], lastPrice: number) {
  const low = Math.min(lastPrice, ...candles.map(candle => candle.lowTicks));
  const high = Math.max(lastPrice, ...candles.map(candle => candle.highTicks));
  const padding = Math.max(12, (high - low) * 0.15);
  return { low: low - padding, high: high + padding };
}
