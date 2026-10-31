package com.northwind.pricing.catalog;

public record Store(String storeId, int ordinal, int priceZoneBps, int salesTaxBps) {
}
