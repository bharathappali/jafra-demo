package com.northwind.pricing.quote;

public final class UnknownItemException extends RuntimeException {
    public UnknownItemException(String message) {
        super(message);
    }
}
