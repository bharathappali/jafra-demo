package com.contoso.freight.tracking.api;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import com.contoso.freight.tracking.shipment.Shipment;
import com.contoso.freight.tracking.shipment.ShipmentStore;
import com.contoso.freight.tracking.view.ShipmentViews;
import com.contoso.freight.tracking.view.TrackingView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public final class PublicTrackingHandler implements HttpHandler {
    private static final byte[] METHOD_NOT_ALLOWED =
            "{\"error\":\"GET required\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NOT_FOUND =
            "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FAILED =
            "{\"error\":\"tracking failed\"}".getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper mapper;
    private final ShipmentStore store;
    private final ShipmentViews views;
    private final RequestMetrics metrics;

    public PublicTrackingHandler(
            ObjectMapper mapper,
            ShipmentStore store,
            ShipmentViews views,
            RequestMetrics metrics) {
        this.mapper = mapper;
        this.store = store;
        this.views = views;
        this.metrics = metrics;
    }

    @Override
    public void handle(HttpExchange exchange) {
        long started = System.nanoTime();
        String client = exchange.getRequestHeaders().getFirst("X-Client-App");
        int status = 500;
        int bytes = 0;
        try {
            Outcome outcome = respond(exchange);
            status = outcome.status;
            bytes = outcome.bytes;
        } catch (Exception exception) {
            status = writeFailure(exchange);
            bytes = FAILED.length;
        } finally {
            metrics.record("public_tracking", client, status, System.nanoTime() - started, bytes);
            exchange.close();
        }
    }

    private Outcome respond(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            return writeRaw(exchange, 405, METHOD_NOT_ALLOWED);
        }
        String path = exchange.getRequestURI().getPath();
        String prefix = "/v1/track/";
        if (!path.startsWith(prefix) || path.length() == prefix.length() || path.indexOf('/', prefix.length()) >= 0) {
            return writeRaw(exchange, 404, NOT_FOUND);
        }
        String trackingNumber = URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8);
        Shipment shipment = store.byTrackingNumber(trackingNumber);
        if (shipment == null) {
            return writeRaw(exchange, 404, NOT_FOUND);
        }
        TrackingView view = views.tracking(shipment);
        return writeJson(exchange, 200, mapper.writeValueAsBytes(view));
    }

    private int writeFailure(HttpExchange exchange) {
        if (exchange.getResponseCode() >= 0) {
            return 500;
        }
        try {
            writeRaw(exchange, 500, FAILED);
        } catch (IOException exception) {
            return 500;
        }
        return 500;
    }

    private static Outcome writeJson(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        return writeRaw(exchange, status, body);
    }

    private static Outcome writeRaw(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        return new Outcome(status, body.length);
    }

    private record Outcome(int status, int bytes) {
    }
}
