package com.northwind.pricing.promo;

public record AppliedPricing(
        long unitPriceCents,
        long unitDiscountCents,
        int bestPromotion,
        int stackedPromotions) {
}
