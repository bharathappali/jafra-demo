package com.northwind.pricing.quote;

import java.util.List;

import com.northwind.pricing.catalog.LoyaltyTier;

/**
 * Everything that influences the promotional unit price of a cart line.
 */
record LinePriceKey(String sku, String storeId, LoyaltyTier tier, List<String> couponCodes, int dayOfWeek) {
}
