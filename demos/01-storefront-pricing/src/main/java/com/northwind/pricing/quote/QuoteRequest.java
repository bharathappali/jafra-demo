package com.northwind.pricing.quote;

import java.util.List;

public record QuoteRequest(
        String cartId,
        String storeId,
        String loyaltyTier,
        List<String> couponCodes,
        List<CartLine> lines) {

    public record CartLine(String sku, int quantity) {
    }
}
