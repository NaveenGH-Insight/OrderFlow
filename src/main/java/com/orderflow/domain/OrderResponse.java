package com.orderflow.domain;

public record OrderResponse(String orderId, String status, long filledQuantity,
                            long remainingQuantity, long averagePriceTicks,
                            String message) {}
