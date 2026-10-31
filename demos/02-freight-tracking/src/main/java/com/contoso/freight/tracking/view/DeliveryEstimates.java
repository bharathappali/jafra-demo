package com.contoso.freight.tracking.view;

import java.time.Duration;
import java.time.Instant;

import com.contoso.freight.tracking.shipment.Shipment;
import com.contoso.freight.tracking.shipment.ShipmentStatus;

public final class DeliveryEstimates {
    private static final int ITINERARY_SCANS = 7;
    private static final int LEGS_AFTER_LABEL = 6;

    private DeliveryEstimates() {
    }

    public static Instant estimate(Shipment shipment, Instant now) {
        if (shipment.status() == ShipmentStatus.DELIVERED) {
            return null;
        }
        if (shipment.status() == ShipmentStatus.EXCEPTION) {
            Instant floor = now.plus(Duration.ofHours(24));
            Instant promisedBy = shipment.promisedBy();
            return promisedBy.isAfter(floor) ? promisedBy : floor;
        }
        if (shipment.status() == ShipmentStatus.OUT_FOR_DELIVERY) {
            Instant fromNow = now.plus(Duration.ofHours(2));
            Instant fromScan = shipment.lastEvent().at().plus(Duration.ofHours(4));
            return fromScan.isAfter(fromNow) ? fromScan : fromNow;
        }
        int remaining = Math.max(1, ITINERARY_SCANS - shipment.events().size());
        Duration leg = shipment.serviceLevel().promise().dividedBy(LEGS_AFTER_LABEL);
        return shipment.lastEvent().at().plus(leg.multipliedBy(remaining));
    }
}
