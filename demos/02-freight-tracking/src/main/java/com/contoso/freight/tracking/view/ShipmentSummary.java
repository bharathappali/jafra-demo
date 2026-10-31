package com.contoso.freight.tracking.view;

import java.util.List;

import com.contoso.freight.tracking.shipment.ServiceLevel;
import com.contoso.freight.tracking.shipment.ShipmentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShipmentSummary(
        String trackingNumber,
        String customerReference,
        ServiceLevel serviceLevel,
        ShipmentStatus status,
        String origin,
        String destination,
        String createdAt,
        String promisedBy,
        String estimatedDelivery,
        boolean late,
        double weightKg,
        int pieces,
        EventView lastEvent,
        List<EventView> events,
        PodSummary proofOfDelivery) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventView(String at, String code, String facility, String description) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PodSummary(
            String signedBy,
            String deliveredAt,
            String leftAt,
            boolean signatureOnFile,
            boolean photoOnFile,
            String photoHref,
            String photoSha256) {
    }
}
