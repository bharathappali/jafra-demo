package com.contoso.freight.tracking.api;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public final class RequestMetrics {
    private static final Set<String> CLIENTS = Set.of("portal", "erp-connector", "tracking-page", "mobile");

    private final MeterRegistry registry;

    public RequestMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String route, String clientApp, int status, long nanos, int bytes) {
        String client = client(clientApp);
        Timer.builder("tracking.http.requests")
                .tag("route", route)
                .tag("client", client)
                .tag("status", Integer.toString(status))
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
        DistributionSummary.builder("tracking.http.response.bytes")
                .tag("route", route)
                .tag("client", client)
                .register(registry)
                .record(bytes);
    }

    static String client(String header) {
        if (header == null || header.isBlank()) {
            return "unknown";
        }
        String name = header.trim();
        int slash = name.indexOf('/');
        if (slash >= 0) {
            name = name.substring(0, slash);
        }
        name = name.toLowerCase(Locale.ROOT);
        if (CLIENTS.contains(name)) {
            return name;
        }
        return "other";
    }
}
