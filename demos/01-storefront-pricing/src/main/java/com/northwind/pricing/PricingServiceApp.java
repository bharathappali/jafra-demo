package com.northwind.pricing;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.northwind.pricing.api.HealthHandler;
import com.northwind.pricing.api.PrometheusHandler;
import com.northwind.pricing.api.QuoteHandler;
import com.northwind.pricing.catalog.ProductCatalog;
import com.northwind.pricing.promo.CouponBook;
import com.northwind.pricing.promo.PromotionCatalog;
import com.northwind.pricing.promo.PromotionEngine;
import com.northwind.pricing.quote.PriceQuoteService;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PricingServiceApp {
    private static final Logger LOG = LoggerFactory.getLogger(PricingServiceApp.class);

    private PricingServiceApp() {
    }

    public static void main(String[] args) throws IOException {
        ServiceConfig config = ServiceConfig.fromEnvironment();
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.config().commonTags("service", "storefront-pricing");
        new JvmMemoryMetrics().bindTo(registry);
        JvmGcMetrics gcMetrics = new JvmGcMetrics();
        gcMetrics.bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);

        ProductCatalog catalog = ProductCatalog.fromSnapshot(
                config.catalogSnapshotSeed(),
                config.productCount(),
                config.storeCount());
        CouponBook coupons = new CouponBook();
        PromotionCatalog promotions = PromotionCatalog.fromDealsSync(
                config.dealsSyncId(),
                config.promotionCount(),
                coupons.size());
        PromotionEngine engine = new PromotionEngine(promotions);
        PriceQuoteService quotes = PriceQuoteService.withLinePriceCache(
                catalog,
                engine,
                coupons,
                config.linePriceCacheSize(),
                registry,
                Clock.systemUTC());

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        ThreadPoolExecutor workers = workers(config.workerThreads());
        HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 512);
        server.setExecutor(workers);
        server.createContext("/v1/quotes", new QuoteHandler(mapper, quotes, registry));
        server.createContext("/health", new HealthHandler());
        server.createContext("/metrics", new PrometheusHandler(registry));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            workers.shutdown();
            gcMetrics.close();
        }, "pricing-shutdown"));
        server.start();
        LOG.info(
                "storefront-pricing listening on {} with {} products, {} stores, {} promotions, {} coupons",
                config.port(),
                catalog.productCount(),
                catalog.stores().size(),
                promotions.promotionCount(),
                coupons.size());
    }

    private static ThreadPoolExecutor workers(int threads) {
        AtomicInteger sequence = new AtomicInteger(1);
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "pricing-worker-" + sequence.getAndIncrement());
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
