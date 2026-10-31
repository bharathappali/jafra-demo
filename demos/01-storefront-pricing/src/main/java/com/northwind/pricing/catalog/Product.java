package com.northwind.pricing.catalog;

public record Product(String sku, int ordinal, int categoryId, int brandId, long listPriceCents) {
}
