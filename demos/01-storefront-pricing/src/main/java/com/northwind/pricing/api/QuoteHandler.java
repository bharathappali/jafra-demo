package com.northwind.pricing.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.northwind.pricing.quote.PriceQuoteService;
import com.northwind.pricing.quote.QuoteRequest;
import com.northwind.pricing.quote.QuoteResponse;
import com.northwind.pricing.quote.UnknownItemException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public final class QuoteHandler implements HttpHandler {
    private static final byte[] METHOD_NOT_ALLOWED =
            "{\"error\":\"POST required\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NOT_FOUND =
            "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper mapper;
    private final PriceQuoteService quotes;
    private final MeterRegistry registry;

    public QuoteHandler(ObjectMapper mapper, PriceQuoteService quotes, MeterRegistry registry) {
        this.mapper = mapper;
        this.quotes = quotes;
        this.registry = registry;
    }

    @Override
    public void handle(HttpExchange exchange) {
        long started = System.nanoTime();
        String channel = channel(exchange);
        int status = 500;
        try {
            status = respond(exchange);
        } catch (Exception exception) {
            status = writeFailure(exchange, exception);
        } finally {
            Timer.builder("pricing.quote.duration")
                    .tag("channel", channel)
                    .tag("status", Integer.toString(status))
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            exchange.close();
        }
    }

    private int respond(HttpExchange exchange) throws IOException {
        if (!"/v1/quotes".equals(exchange.getRequestURI().getPath())) {
            try (InputStream input = exchange.getRequestBody()) {
                input.readAllBytes();
            }
            writeRaw(exchange, 404, NOT_FOUND);
            return 404;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            try (InputStream input = exchange.getRequestBody()) {
                input.readAllBytes();
            }
            writeRaw(exchange, 405, METHOD_NOT_ALLOWED);
            return 405;
        }
        final QuoteRequest request;
        try (InputStream input = exchange.getRequestBody()) {
            request = mapper.readValue(input, QuoteRequest.class);
        } catch (JsonProcessingException exception) {
            writeJson(exchange, 400, errorBody(exception.getOriginalMessage()));
            return 400;
        }
        try {
            QuoteResponse response = quotes.quote(request);
            writeJson(exchange, 200, mapper.writeValueAsBytes(response));
            return 200;
        } catch (UnknownItemException exception) {
            writeJson(exchange, 422, errorBody(exception.getMessage()));
            return 422;
        } catch (IllegalArgumentException exception) {
            writeJson(exchange, 400, errorBody(exception.getMessage()));
            return 400;
        }
    }

    private int writeFailure(HttpExchange exchange, Exception exception) {
        if (exchange.getResponseCode() >= 0) {
            return 500;
        }
        try {
            writeJson(exchange, 500, errorBody("quote failed"));
        } catch (IOException ignored) {
            return 500;
        }
        return 500;
    }

    private byte[] errorBody(String message) throws JsonProcessingException {
        String text = message == null || message.isBlank() ? "request" : message;
        return mapper.writeValueAsBytes(Map.of("error", text));
    }

    private static void writeJson(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        writeRaw(exchange, status, body);
    }

    private static void writeRaw(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static String channel(HttpExchange exchange) {
        String value = exchange.getRequestHeaders().getFirst("X-Client-Channel");
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim();
    }
}
