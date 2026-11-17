package com.orderflow.engine;

import com.orderflow.domain.BookLevel;
import com.orderflow.domain.Candle;
import com.orderflow.domain.MarketSnapshot;
import com.orderflow.domain.OrderRequest;
import com.orderflow.domain.OrderResponse;
import com.orderflow.domain.OrderSide;
import com.orderflow.domain.OrderType;
import com.orderflow.domain.Trade;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Component
public class MatchingEngine {
    private static final int BOOK_DEPTH = 20;
    private static final int RECENT_TRADES = 80;
    private static final int CANDLE_CAPACITY = 120;
    private final RiskLedger riskLedger;
    private final Consumer<Trade> tradePublisher;
    private final Clock clock;
    private final TreeMap<Long, Deque<RestingOrder>> bids = new TreeMap<>(java.util.Comparator.reverseOrder());
    private final TreeMap<Long, Deque<RestingOrder>> asks = new TreeMap<>();
    private final Map<String, RestingOrder> openOrders = new HashMap<>();
    private final Deque<Trade> trades = new ArrayDeque<>();
    private final Deque<Candle> candles = new ArrayDeque<>();
    private final AtomicLong tradeSequence = new AtomicLong();
    private long lastPriceTicks = 18_640;
    private long marketSequence;
    private long quoteSequence;
    private Candle activeCandle;

    public MatchingEngine(RiskLedger riskLedger, TradeBroadcaster tradePublisher) {
        this(riskLedger, tradePublisher, Clock.systemUTC());
    }

    @Autowired
    public MatchingEngine(RiskLedger riskLedger, TradeBroadcaster tradePublisher, Clock clock) {
        this.riskLedger = riskLedger;
        this.tradePublisher = tradePublisher::publish;
        this.clock = clock;
    }

    public synchronized long bestOpposingPrice(OrderSide side) {
        TreeMap<Long, Deque<RestingOrder>> opposing = side == OrderSide.BUY ? asks : bids;
        return opposing.isEmpty() ? 0 : opposing.firstKey();
    }

    public synchronized int openOrderCount() { return openOrders.size(); }

    public synchronized OrderResponse place(String orderId, OrderRequest request, long effectiveLimitTicks) {
        long remaining = request.quantity();
        long filled = 0;
        long notional = 0;
        boolean selfTradePrevented = false;
        TreeMap<Long, Deque<RestingOrder>> opposing = request.side() == OrderSide.BUY ? asks : bids;
        while (remaining > 0 && !opposing.isEmpty()) {
            Map.Entry<Long, Deque<RestingOrder>> level = opposing.firstEntry();
            long price = level.getKey();
            boolean crosses = request.side() == OrderSide.BUY
                    ? price <= effectiveLimitTicks : price >= effectiveLimitTicks;
            if (!crosses) break;
            RestingOrder maker = level.getValue().peekFirst();
            if (maker.accountId.equals(request.accountId())) {
                selfTradePrevented = true;
                break;
            }
            long matchQuantity = Math.min(remaining, maker.remaining);
            String buyer = request.side() == OrderSide.BUY ? request.accountId() : maker.accountId;
            String seller = request.side() == OrderSide.SELL ? request.accountId() : maker.accountId;
            long buyerLimit = request.side() == OrderSide.BUY ? effectiveLimitTicks : maker.priceTicks;
            riskLedger.settle(buyer, seller, buyerLimit, price, matchQuantity);
            Trade trade = new Trade(Long.toString(tradeSequence.incrementAndGet()),
                    request.symbol(), price, matchQuantity, request.side().name(), clock.instant());
            appendTrade(trade);
            tradePublisher.accept(trade);
            maker.remaining -= matchQuantity;
            remaining -= matchQuantity;
            filled += matchQuantity;
            notional = Math.addExact(notional, Math.multiplyExact(price, matchQuantity));
            marketSequence++;
            if (maker.remaining == 0) {
                level.getValue().removeFirst();
                openOrders.remove(maker.orderId);
                if (level.getValue().isEmpty()) opposing.remove(price);
            }
        }

        if (remaining > 0 && request.type() == OrderType.LIMIT && !selfTradePrevented) {
            RestingOrder resting = new RestingOrder(orderId, request.accountId(), request.side(),
                    request.priceTicks(), remaining);
            TreeMap<Long, Deque<RestingOrder>> ownSide = request.side() == OrderSide.BUY ? bids : asks;
            ownSide.computeIfAbsent(request.priceTicks(), ignored -> new ArrayDeque<>()).addLast(resting);
            openOrders.put(orderId, resting);
        } else if (remaining > 0) {
            riskLedger.release(request.accountId(), request.side(), effectiveLimitTicks, remaining);
        }

        String status = selfTradePrevented && filled == 0 ? "SELF_TRADE_PREVENTED"
                : request.type() == OrderType.MARKET
                ? (remaining == 0 ? "FILLED" : (filled > 0 ? "PARTIALLY_FILLED" : "CANCELLED"))
                : (remaining == 0 ? (filled > 0 ? "FILLED" : "RESTING")
                : (filled > 0 ? "PARTIALLY_FILLED" : "RESTING"));
        long average = filled == 0 ? 0 : notional / filled;
        marketSequence++;
        return new OrderResponse(orderId, status, filled, remaining, average,
                selfTradePrevented ? "Self-trade prevention released the unfilled quantity."
                        : request.type() == OrderType.MARKET ? "Market protection applied; unfilled quantity released."
                        : "Price-time priority applied.");
    }

    public synchronized OrderResponse cancel(String accountId, String orderId) {
        RestingOrder order = openOrders.get(orderId);
        if (order == null || !order.accountId.equals(accountId)) {
            throw new IllegalArgumentException("Open order not found for this account");
        }

        TreeMap<Long, Deque<RestingOrder>> side = order.side == OrderSide.BUY ? bids : asks;
        Deque<RestingOrder> level = side.get(order.priceTicks);
        level.remove(order);
        if (level.isEmpty()) side.remove(order.priceTicks);
        openOrders.remove(orderId);
        riskLedger.release(accountId, order.side, order.priceTicks, order.remaining);
        marketSequence++;
        return new OrderResponse(orderId, "CANCELLED", order.quantity - order.remaining,
                order.remaining, 0, "Remaining funds or shares released.");
    }

    public synchronized int cancelAccountOrders(String accountId) {
        int cancelled = 0;
        var iterator = openOrders.values().iterator();
        while (iterator.hasNext()) {
            RestingOrder order = iterator.next();
            if (!order.accountId.equals(accountId)) continue;
            TreeMap<Long, Deque<RestingOrder>> side = order.side == OrderSide.BUY ? bids : asks;
            Deque<RestingOrder> level = side.get(order.priceTicks);
            level.remove(order);
            if (level.isEmpty()) side.remove(order.priceTicks);
            riskLedger.release(accountId, order.side, order.priceTicks, order.remaining);
            iterator.remove();
            cancelled++;
        }
        marketSequence++;
        return cancelled;
    }

    public synchronized int replaceSimulationQuotes(List<OrderRequest> quotes) {
        cancelAccountOrders("market-maker");
        for (OrderRequest quote : quotes) {
            riskLedger.reserve(quote.accountId(), quote.side(), quote.priceTicks(), quote.quantity());
            place("quote-" + ++quoteSequence, quote, quote.priceTicks());
        }
        return quotes.size();
    }

    public synchronized MarketSnapshot snapshot(String accountId) {
        List<BookLevel> bidLevels = levels(bids);
        List<BookLevel> askLevels = levels(asks);
        long bestBid = bids.isEmpty() ? 0 : bids.firstKey();
        long bestAsk = asks.isEmpty() ? 0 : asks.firstKey();
        List<Candle> candleView = new ArrayList<>(candles);
        if (activeCandle != null) candleView.add(activeCandle);
        return new MarketSnapshot("ORDF", marketSequence, lastPriceTicks, bestBid, bestAsk,
                bidLevels, askLevels, List.copyOf(trades), List.copyOf(candleView),
                riskLedger.availableCash(accountId), riskLedger.availableShares(accountId), 0, 0);
    }

    private List<BookLevel> levels(TreeMap<Long, Deque<RestingOrder>> side) {
        List<BookLevel> result = new ArrayList<>(BOOK_DEPTH);
        for (Map.Entry<Long, Deque<RestingOrder>> level : side.entrySet()) {
            long quantity = 0;
            for (RestingOrder order : level.getValue()) quantity += order.remaining;
            result.add(new BookLevel(level.getKey(), quantity, level.getValue().size()));
            if (result.size() == BOOK_DEPTH) break;
        }
        return List.copyOf(result);
    }

    private void appendTrade(Trade trade) {
        lastPriceTicks = trade.priceTicks();
        trades.addFirst(trade);
        while (trades.size() > RECENT_TRADES) trades.removeLast();
        long bucket = (trade.timestamp().toEpochMilli() / 60_000) * 60_000;
        if (activeCandle == null || activeCandle.openTimeMillis() != bucket) {
            if (activeCandle != null) candles.addLast(activeCandle);
            activeCandle = new Candle(bucket, trade.priceTicks(), trade.priceTicks(),
                    trade.priceTicks(), trade.priceTicks(), trade.quantity());
            while (candles.size() >= CANDLE_CAPACITY) candles.removeFirst();
        } else {
            activeCandle = new Candle(bucket, activeCandle.openTicks(),
                    Math.max(activeCandle.highTicks(), trade.priceTicks()),
                    Math.min(activeCandle.lowTicks(), trade.priceTicks()),
                    trade.priceTicks(), activeCandle.volume() + trade.quantity());
        }
    }

    private static final class RestingOrder {
        private final String orderId;
        private final String accountId;
        private final OrderSide side;
        private final long priceTicks;
        private final long quantity;
        private long remaining;

        private RestingOrder(String orderId, String accountId, OrderSide side, long priceTicks, long quantity) {
            this.orderId = orderId;
            this.accountId = accountId;
            this.side = side;
            this.priceTicks = priceTicks;
            this.quantity = quantity;
            this.remaining = quantity;
        }
    }
}
