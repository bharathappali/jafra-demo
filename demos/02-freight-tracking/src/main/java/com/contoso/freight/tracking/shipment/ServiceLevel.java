package com.contoso.freight.tracking.shipment;

import java.time.Duration;

public enum ServiceLevel {
    SAME_DAY(Duration.ofHours(10)),
    NEXT_DAY(Duration.ofHours(30)),
    TWO_DAY(Duration.ofHours(54)),
    GROUND(Duration.ofDays(5));

    private final Duration promise;

    ServiceLevel(Duration promise) {
        this.promise = promise;
    }

    public Duration promise() {
        return promise;
    }
}
