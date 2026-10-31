package com.northwind.pricing.quote;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.northwind.pricing.catalog.LoyaltyTier;
import com.northwind.pricing.catalog.Product;
import com.northwind.pricing.catalog.ProductCatalog;
import com.northwind.pricing.catalog.Store;
import com.northwind.pricing.promo.AppliedPricing;
import com.northwind.pricing.promo.CouponBook;
import com.northwind.pricing.promo.PromotionEngine;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;

public final class PriceQuoteService {
    private final ProductCatalog catalog;
    private final PromotionEngine engine;
    private final CouponBook couponBook;
    private final Cache<LinePriceKey, AppliedPricing> linePrices;
    private final Clock clock;

    private PriceQuoteService(
            ProductCatalog catalog,
            PromotionEngine engine,
            CouponBook couponBook,
            Cache<LinePriceKey, AppliedPricing> linePrices,
            Clock clock) {
        this.catalog = catalog;
        this.engine = engine;
        this.couponBook = couponBook;
        this.linePrices = linePrices;
        this.clock = clock;
    }

    public static PriceQuoteService withLinePriceCache(
            ProductCatalog catalog,
            PromotionEngine engine,
            CouponBook couponBook,
            long maxEntries,
            MeterRegistry registry,
            Clock clock) {
        Cache<LinePriceKey, AppliedPricing> cache = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(registry, cache, "line_price");
        return new PriceQuoteService(catalog, engine, couponBook, cache, clock);
    }

    public QuoteResponse quote(QuoteRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request");
        }
        Store store = catalog.store(request.storeId())
                .orElseThrow(() -> new UnknownItemException("Unknown store " + request.storeId()));
        LoyaltyTier tier = LoyaltyTier.parse(request.loyaltyTier());
        List<String> coupons = CouponCodes.normalize(request.couponCodes());
        int dayOfWeek = ZonedDateTime.now(clock).getDayOfWeek().getValue() - 1;
        List<QuoteRequest.CartLine> requestLines = request.lines() == null ? List.of() : request.lines();
        List<QuoteResponse.QuotedLine> quoted = new ArrayList<>(requestLines.size());
        long subtotal = 0;
        long discount = 0;
        for (QuoteRequest.CartLine line : requestLines) {
            if (line == null || line.sku() == null || line.sku().isBlank()) {
                throw new IllegalArgumentException("sku");
            }
            if (line.quantity() < 1) {
                throw new IllegalArgumentException("quantity");
            }
            Product product = catalog.product(line.sku())
                    .orElseThrow(() -> new UnknownItemException("Unknown SKU " + line.sku()));
            AppliedPricing pricing = unitPrice(product, store, tier, coupons, dayOfWeek);
            long netUnit = Math.max(0L, pricing.unitPriceCents() - pricing.unitDiscountCents());
            long lineTotal = QuantityBreaks.lineTotal(netUnit, line.quantity());
            long gross = pricing.unitPriceCents() * (long) line.quantity();
            subtotal += lineTotal;
            discount += gross - lineTotal;
            quoted.add(new QuoteResponse.QuotedLine(
                    product.sku(),
                    line.quantity(),
                    pricing.unitPriceCents(),
                    pricing.unitDiscountCents(),
                    lineTotal,
                    pricing.bestPromotion()));
        }
        long tax = subtotal * store.salesTaxBps() / 10_000L;
        return new QuoteResponse(
                request.cartId(),
                store.storeId(),
                List.copyOf(quoted),
                subtotal,
                discount,
                tax,
                subtotal + tax);
    }

    private AppliedPricing unitPrice(
            Product product,
            Store store,
            LoyaltyTier tier,
            List<String> coupons,
            int dayOfWeek) {
        LinePriceKey key = new LinePriceKey(product.sku(), store.storeId(), tier, coupons, dayOfWeek);
        AppliedPricing cached = linePrices.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        AppliedPricing computed = engine.applyPromotions(
                product,
                store,
                tier,
                couponBook.resolve(coupons),
                dayOfWeek);
        linePrices.put(key, computed);
        return computed;
    }
}
