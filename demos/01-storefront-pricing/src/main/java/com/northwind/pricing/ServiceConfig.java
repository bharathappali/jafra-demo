package com.northwind.pricing;

public record ServiceConfig(
        int port,
        int workerThreads,
        long linePriceCacheSize,
        int promotionCount,
        int productCount,
        int storeCount,
        long catalogSnapshotSeed,
        long dealsSyncId) {

    public ServiceConfig {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("PORT");
        }
        if (workerThreads < 1) {
            throw new IllegalArgumentException("WORKER_THREADS");
        }
        if (linePriceCacheSize < 1) {
            throw new IllegalArgumentException("LINE_PRICE_CACHE_SIZE");
        }
        if (promotionCount < 1) {
            throw new IllegalArgumentException("PROMOTION_COUNT");
        }
        if (productCount < 1) {
            throw new IllegalArgumentException("PRODUCT_COUNT");
        }
        if (storeCount < 1) {
            throw new IllegalArgumentException("STORE_COUNT");
        }
    }

    public static ServiceConfig fromEnvironment() {
        return new ServiceConfig(
                envInt("PORT", 8080),
                envInt("WORKER_THREADS", 32),
                envLong("LINE_PRICE_CACHE_SIZE", 200_000L),
                envInt("PROMOTION_COUNT", 120_000),
                envInt("PRODUCT_COUNT", 5_000),
                envInt("STORE_COUNT", 40),
                envLong("CATALOG_SNAPSHOT_SEED", 20261031L),
                envLong("DEALS_SYNC_ID", 7741L));
    }

    private static int envInt(String name, int fallback) {
        String value = env(name);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static long envLong(String name, long fallback) {
        String value = env(name);
        return value == null ? fallback : Long.parseLong(value);
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
