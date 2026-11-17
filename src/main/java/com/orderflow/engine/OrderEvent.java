package com.orderflow.engine;

import com.orderflow.domain.OrderRequest;

import java.util.concurrent.CompletableFuture;

final class OrderEvent {
    static final int PLACE = 1;
    static final int CANCEL = 2;

    int action;
    String orderId;
    String accountId;
    OrderRequest request;
    long effectiveLimitTicks;
    long startedNanos;
    CompletableFuture<Object> result;

    void clear() {
        action = 0;
        orderId = null;
        accountId = null;
        request = null;
        result = null;
    }
}
