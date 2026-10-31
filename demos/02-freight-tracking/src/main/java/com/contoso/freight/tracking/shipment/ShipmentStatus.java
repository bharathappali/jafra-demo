package com.contoso.freight.tracking.shipment;

public enum ShipmentStatus {
    LABEL_CREATED,
    PICKED_UP,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    EXCEPTION;

    public boolean active() {
        return this != DELIVERED;
    }
}
