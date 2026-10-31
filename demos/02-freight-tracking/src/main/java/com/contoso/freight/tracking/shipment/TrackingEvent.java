package com.contoso.freight.tracking.shipment;

import java.time.Instant;

public record TrackingEvent(Instant at, String code, String facility, String description) {
}
