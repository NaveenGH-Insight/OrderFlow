import test from "node:test";
import assert from "node:assert/strict";
import { aggregateCandles, priceRange } from "../src/market.ts";

test("five-minute candles preserve execution open/high/low/close and summed volume without mutation", () => {
  const source = [
    { openTimeMillis: 0, openTicks: 18600, highTicks: 18620, lowTicks: 18590, closeTicks: 18610, volume: 8 },
    { openTimeMillis: 60000, openTicks: 18608, highTicks: 18630, lowTicks: 18600, closeTicks: 18624, volume: 12 },
    { openTimeMillis: 300000, openTicks: 18625, highTicks: 18627, lowTicks: 18600, closeTicks: 18602, volume: 5 }
  ];
  assert.deepEqual(aggregateCandles(source, 5), [
    { openTimeMillis: 0, openTicks: 18600, highTicks: 18630, lowTicks: 18590, closeTicks: 18624, volume: 20 },
    source[2]
  ]);
  assert.equal(source[0].volume, 8);
});

test("flat or single-candle charts retain padded non-zero price scale", () => {
  const candle = { openTimeMillis: 0, openTicks: 18640, highTicks: 18640, lowTicks: 18640, closeTicks: 18640, volume: 2 };
  const range = priceRange([candle], 18640);
  assert.ok(range.low < 18640 && range.high > 18640);
  assert.ok(range.high - range.low >= 24);
  assert.deepEqual(aggregateCandles([], 1), []);
});
