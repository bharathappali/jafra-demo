package com.contoso.freight.tracking;

public record ServiceConfig(
        int port,
        int workerThreads,
        int accountCount,
        int shipmentCount,
        int retentionDays,
        int maxPageSize,
        long snapshotSeed) {

    public ServiceConfig {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("PORT");
        }
        if (workerThreads < 1) {
            throw new IllegalArgumentException("WORKER_THREADS");
        }
        if (accountCount < 1) {
            throw new IllegalArgumentException("ACCOUNT_COUNT");
        }
        if (shipmentCount < 1) {
            throw new IllegalArgumentException("SHIPMENT_COUNT");
        }
        if (retentionDays < 1) {
            throw new IllegalArgumentException("RETENTION_DAYS");
        }
        if (maxPageSize < 1) {
            throw new IllegalArgumentException("MAX_PAGE_SIZE");
        }
    }

    public static ServiceConfig fromEnvironment() {
        return new ServiceConfig(
                envInt("PORT", 8080),
                envInt("WORKER_THREADS", 32),
                envInt("ACCOUNT_COUNT", 400),
                envInt("SHIPMENT_COUNT", 16_000),
                envInt("RETENTION_DAYS", 10),
                envInt("MAX_PAGE_SIZE", 100),
                envLong("SNAPSHOT_SEED", 20261031L));
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
