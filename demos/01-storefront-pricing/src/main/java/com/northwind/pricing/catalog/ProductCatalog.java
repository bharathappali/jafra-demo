package com.northwind.pricing.catalog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;

/**
 * Read-only merchandising snapshot materialised from the export seed.
 */
public final class ProductCatalog {
    public static final int CATEGORY_COUNT = 256;
    public static final int BRAND_COUNT = 512;

    private final Map<String, Product> productsBySku;
    private final Map<String, Store> storesById;
    private final List<Store> stores;

    private ProductCatalog(Map<String, Product> productsBySku, Map<String, Store> storesById, List<Store> stores) {
        this.productsBySku = productsBySku;
        this.storesById = storesById;
        this.stores = stores;
    }

    public static ProductCatalog fromSnapshot(long seed, int productCount, int storeCount) {
        if (productCount < 1) {
            throw new IllegalArgumentException("productCount");
        }
        if (storeCount < 1 || storeCount > Long.SIZE) {
            throw new IllegalArgumentException("storeCount exceeds store mask");
        }
        SplittableRandom random = new SplittableRandom(seed);
        Map<String, Product> products = new HashMap<>(productCount * 2);
        for (int i = 0; i < productCount; i++) {
            Product product = new Product(
                    String.format("SKU-%05d", i),
                    i,
                    random.nextInt(CATEGORY_COUNT),
                    random.nextInt(BRAND_COUNT),
                    199 + random.nextLong(24_800));
            products.put(product.sku(), product);
        }
        Map<String, Store> storesById = new HashMap<>(storeCount * 2);
        Store[] stores = new Store[storeCount];
        for (int i = 0; i < storeCount; i++) {
            Store store = new Store(
                    String.format("ST-%03d", i + 1),
                    i,
                    9_400 + random.nextInt(1_300),
                    400 + random.nextInt(650));
            stores[i] = store;
            storesById.put(store.storeId(), store);
        }
        return new ProductCatalog(Map.copyOf(products), Map.copyOf(storesById), List.of(stores));
    }

    public Optional<Product> product(String sku) {
        if (sku == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(productsBySku.get(sku));
    }

    public Optional<Store> store(String storeId) {
        if (storeId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(storesById.get(storeId));
    }

    public int productCount() {
        return productsBySku.size();
    }

    public List<Store> stores() {
        return stores;
    }
}
