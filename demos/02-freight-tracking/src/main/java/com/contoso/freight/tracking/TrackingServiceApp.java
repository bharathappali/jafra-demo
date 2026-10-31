package com.contoso.freight.tracking;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.contoso.freight.tracking.api.AccountShipmentsHandler;
import com.contoso.freight.tracking.api.HealthHandler;
import com.contoso.freight.tracking.api.PrometheusHandler;
import com.contoso.freight.tracking.api.PublicTrackingHandler;
import com.contoso.freight.tracking.api.RequestMetrics;
import com.contoso.freight.tracking.shipment.ShipmentStore;
import com.contoso.freight.tracking.shipment.SnapshotLoader;
import com.contoso.freight.tracking.view.ShipmentViews;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TrackingServiceApp {
    private static final Logger LOG = LoggerFactory.getLogger(TrackingServiceApp.class);

    private TrackingServiceApp() {
    }

    public static void main(String[] args) throws IOException {
        ServiceConfig config = ServiceConfig.fromEnvironment();
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.config().commonTags("service", "shipment-tracking");
        new JvmMemoryMetrics().bindTo(registry);
        JvmGcMetrics gcMetrics = new JvmGcMetrics();
        gcMetrics.bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);

        ShipmentStore store = new SnapshotLoader(config.snapshotSeed(), Clock.systemUTC())
                .load(config.accountCount(), config.shipmentCount(), config.retentionDays());
        ShipmentViews views = new ShipmentViews(Clock.systemUTC());
        ObjectMapper mapper = new ObjectMapper();
        RequestMetrics metrics = new RequestMetrics(registry);

        ThreadPoolExecutor workers = workers(config.workerThreads());
        HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 512);
        server.setExecutor(workers);
        server.createContext(
                "/v1/accounts/",
                new AccountShipmentsHandler(mapper, store, views, metrics, config.maxPageSize()));
        server.createContext("/v1/track/", new PublicTrackingHandler(mapper, store, views, metrics));
        server.createContext("/health", new HealthHandler());
        server.createContext("/metrics", new PrometheusHandler(registry));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            workers.shutdown();
            gcMetrics.close();
        }, "tracking-shutdown"));
        server.start();
        LOG.info(
                "shipment-tracking listening on {} with {} shipments, {} accounts, {} delivered",
                config.port(),
                store.size(),
                store.accountCount(),
                store.deliveredCount());
    }

    private static ThreadPoolExecutor workers(int threads) {
        AtomicInteger sequence = new AtomicInteger(1);
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "tracking-worker-" + sequence.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        };
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(10_000),
                factory);
    }
}
