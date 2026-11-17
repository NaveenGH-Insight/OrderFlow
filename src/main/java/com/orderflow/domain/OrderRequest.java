package com.orderflow.domain;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record OrderRequest(
        @NotBlank @Pattern(regexp = "^[A-Z0-9._-]{1,12}$") String symbol,
        @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_-]{1,32}$") String accountId,
        @NotNull OrderSide side,
        @NotNull OrderType type,
        @Min(0) @Max(100_000_000) long priceTicks,
        @Min(1) @Max(100_000) long quantity
) {}
