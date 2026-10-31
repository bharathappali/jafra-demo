package com.contoso.freight.tracking.api;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.contoso.freight.tracking.shipment.Shipment;
import com.contoso.freight.tracking.shipment.ShipmentStatus;
import com.contoso.freight.tracking.shipment.ShipmentStore;
import com.contoso.freight.tracking.view.Include;
import com.contoso.freight.tracking.view.ShipmentSummary;
import com.contoso.freight.tracking.view.ShipmentViews;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public final class AccountShipmentsHandler implements HttpHandler {
    private static final byte[] METHOD_NOT_ALLOWED =
            "{\"error\":\"GET required\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NOT_FOUND =
            "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BAD_REQUEST =
            "{\"error\":\"bad request\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FAILED =
            "{\"error\":\"request failed\"}".getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper mapper;
    private final ShipmentStore store;
    private final ShipmentViews views;
    private final RequestMetrics metrics;
    private final int maxPageSize;

    public AccountShipmentsHandler(
            ObjectMapper mapper,
            ShipmentStore store,
            ShipmentViews views,
            RequestMetrics metrics,
            int maxPageSize) {
        this.mapper = mapper;
        this.store = store;
        this.views = views;
        this.metrics = metrics;
        this.maxPageSize = maxPageSize;
    }

    @Override
    public void handle(HttpExchange exchange) {
        long started = System.nanoTime();
        String client = exchange.getRequestHeaders().getFirst("X-Client-App");
        String route = "account_shipments";
        int status = 500;
        int bytes = 0;
        try {
            Outcome outcome = respond(exchange);
            route = outcome.route;
            status = outcome.status;
            bytes = outcome.bytes;
        } catch (Exception exception) {
            status = writeFailure(exchange);
            bytes = status == 500 ? FAILED.length : 0;
        } finally {
            metrics.record(route, client, status, System.nanoTime() - started, bytes);
            exchange.close();
        }
    }

    private Outcome respond(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            return writeRaw(exchange, "account_shipments", 405, METHOD_NOT_ALLOWED);
        }
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");
        if (parts.length == 5 && "shipments".equals(parts[4])) {
            return shipments(exchange, decode(parts[3]));
        }
        if (parts.length == 8
                && "shipments".equals(parts[4])
                && "pod".equals(parts[6])
                && "photo".equals(parts[7])) {
            return photo(exchange, decode(parts[3]), decode(parts[5]));
        }
        return writeRaw(exchange, "account_shipments", 404, NOT_FOUND);
    }

    private Outcome shipments(HttpExchange exchange, String accountId) throws IOException {
        if (!store.knowsAccount(accountId)) {
            return writeRaw(exchange, "account_shipments", 404, NOT_FOUND);
        }
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        final Predicate<Shipment> filter;
        final int limit;
        final int offset;
        final Set<Include> includes;
        try {
            filter = statusFilter(query.get("status"));
            limit = limit(query.get("limit"));
            offset = Cursors.decode(query.get("cursor"));
            includes = Include.parse(query.get("include"));
        } catch (IllegalArgumentException exception) {
            return writeRaw(exchange, "account_shipments", 400, BAD_REQUEST);
        }
        ShipmentStore.Page page = store.page(accountId, filter, offset, limit);
        String nextCursor = page.nextOffset() < 0 ? null : Cursors.encode(page.nextOffset());
        List<ShipmentSummary> items = views.summaries(page.items(), includes);
        byte[] body = mapper.writeValueAsBytes(new AccountShipmentList(accountId, items, nextCursor));
        return writeJson(exchange, "account_shipments", 200, body);
    }

    private Outcome photo(HttpExchange exchange, String accountId, String trackingNumber) throws IOException {
        Shipment shipment = store.byTrackingNumber(trackingNumber);
        if (shipment == null || !accountId.equals(shipment.accountId())) {
            return writeRaw(exchange, "pod_photo", 404, NOT_FOUND);
        }
        var proof = views.customerProofOfDelivery(shipment);
        if (proof == null || !proof.hasPhoto()) {
            return writeRaw(exchange, "pod_photo", 404, NOT_FOUND);
        }
        byte[] photo = proof.photoJpeg();
        exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
        exchange.getResponseHeaders().set("Cache-Control", "private, max-age=86400");
        return writeRaw(exchange, "pod_photo", 200, photo);
    }

    private Predicate<Shipment> statusFilter(String raw) {
        if (raw == null || raw.isBlank() || "ACTIVE".equalsIgnoreCase(raw)) {
            return shipment -> shipment.status().active();
        }
        if ("ALL".equalsIgnoreCase(raw)) {
            return shipment -> true;
        }
        final ShipmentStatus status;
        try {
            status = ShipmentStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("status");
        }
        return shipment -> shipment.status() == status;
    }

    private int limit(String raw) {
        if (raw == null || raw.isBlank()) {
            return 25;
        }
        final int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("limit");
        }
        if (parsed < 1) {
            return 1;
        }
        return Math.min(parsed, maxPageSize);
    }

    private int writeFailure(HttpExchange exchange) {
        if (exchange.getResponseCode() >= 0) {
            return 500;
        }
        try {
            writeRaw(exchange, "account_shipments", 500, FAILED);
        } catch (IOException exception) {
            return 500;
        }
        return 500;
    }

    private static Outcome writeJson(HttpExchange exchange, String route, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        return writeRaw(exchange, route, status, body);
    }

    private static Outcome writeRaw(HttpExchange exchange, String route, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        return new Outcome(route, status, body.length);
    }

    private static Map<String, String> query(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                params.put(decode(part), "");
            } else {
                params.put(decode(part.substring(0, eq)), decode(part.substring(eq + 1)));
            }
        }
        return params;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record AccountShipmentList(String accountId, List<ShipmentSummary> items, String nextCursor) {
    }

    private record Outcome(String route, int status, int bytes) {
    }
}
