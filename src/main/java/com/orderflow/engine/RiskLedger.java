package com.orderflow.engine;

import com.orderflow.domain.OrderSide;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RiskLedger {
    private final ConcurrentHashMap<String, Account> accounts = new ConcurrentHashMap<>();

    public RiskLedger() {
        fund("student", 2_500_000, 10_000);
        fund("market-maker", 100_000_000, 1_000_000);
        fund("demo-flow", 10_000_000, 100_000);
    }

    public void fund(String accountId, long cashTicks, long shares) {
        if (cashTicks < 0 || shares < 0) throw new IllegalArgumentException("Starting balances must be non-negative");
        accounts.put(accountId, new Account(cashTicks, shares));
    }

    public void reserve(String accountId, OrderSide side, long limitTicks, long quantity) {
        Account account = accounts.get(accountId);
        if (account == null) throw new IllegalArgumentException("Unknown demo account");
        if (side == OrderSide.BUY) {
            long amount;
            try {
                amount = Math.multiplyExact(limitTicks, quantity);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("Order notional is too large");
            }
            debit(account.cash, amount, "Insufficient simulated funds");
        } else {
            debit(account.shares, quantity, "Insufficient simulated shares");
        }
    }

    public void release(String accountId, OrderSide side, long limitTicks, long quantity) {
        Account account = requireAccount(accountId);
        if (side == OrderSide.BUY) account.cash.addAndGet(Math.multiplyExact(limitTicks, quantity));
        else account.shares.addAndGet(quantity);
    }

    public void settle(String buyer, String seller, long buyerLimitTicks, long executionTicks, long quantity) {
        Account buyerAccount = requireAccount(buyer);
        Account sellerAccount = requireAccount(seller);
        buyerAccount.cash.addAndGet(Math.multiplyExact(buyerLimitTicks - executionTicks, quantity));
        sellerAccount.cash.addAndGet(Math.multiplyExact(executionTicks, quantity));
        buyerAccount.shares.addAndGet(quantity);
    }

    public long availableCash(String accountId) { return requireAccount(accountId).cash.get(); }
    public long availableShares(String accountId) { return requireAccount(accountId).shares.get(); }

    private Account requireAccount(String accountId) {
        Account account = accounts.get(accountId);
        if (account == null) throw new IllegalArgumentException("Unknown demo account");
        return account;
    }

    private static void debit(AtomicLong balance, long amount, String message) {
        long current;
        do {
            current = balance.get();
            if (current < amount) throw new IllegalArgumentException(message);
        } while (!balance.compareAndSet(current, current - amount));
    }

    private record Account(AtomicLong cash, AtomicLong shares) {
        Account(long cash, long shares) { this(new AtomicLong(cash), new AtomicLong(shares)); }
    }
}
