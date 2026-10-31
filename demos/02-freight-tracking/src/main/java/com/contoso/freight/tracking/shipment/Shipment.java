package com.contoso.freight.tracking.shipment;

import java.time.Instant;
import java.util.List;

public record Shipment(
        String trackingNumber,
        String accountId,
        String customerReference,
        ServiceLevel serviceLevel,
        String originCity,
        String destinationCity,
        String destinationPostcode,
        Instant createdAt,
        Instant promisedBy,
        ShipmentStatus status,
        List<TrackingEvent> events,
        double weightKg,
        int pieces,
        ProofOfDelivery proofOfDelivery) {

    public TrackingEvent lastEvent() {
        return events.get(events.size() - 1);
    }
}
