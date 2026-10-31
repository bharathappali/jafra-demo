package com.contoso.freight.tracking.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public final class HealthHandler implements HttpHandler {
    private static final byte[] UP = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, UP.length);
            exchange.getResponseBody().write(UP);
        } finally {
            exchange.close();
        }
    }
}
