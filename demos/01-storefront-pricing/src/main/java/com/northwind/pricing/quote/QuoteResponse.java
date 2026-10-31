package com.northwind.pricing.quote;

import java.util.List;

public record QuoteResponse(
        String cartId,
        String storeId,
        List<QuotedLine> lines,
        long subtotalCents,
        long discountCents,
        long taxCents,
        long totalCents) {

    public record QuotedLine(
            String sku,
            int quantity,
            long unitPriceCents,
            long unitDiscountCents,
            long lineTotalCents,
            int promotionId) {
    }
}
