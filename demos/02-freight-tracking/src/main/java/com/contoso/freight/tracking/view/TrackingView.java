package com.contoso.freight.tracking.view;

import java.util.List;

import com.contoso.freight.tracking.shipment.ServiceLevel;
import com.contoso.freight.tracking.shipment.ShipmentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrackingView(
        String trackingNumber,
        ShipmentStatus status,
        ServiceLevel serviceLevel,
        String destination,
        String estimatedDelivery,
        List<ShipmentSummary.EventView> events) {
}
