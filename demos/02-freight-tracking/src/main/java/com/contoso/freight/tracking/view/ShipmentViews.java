package com.contoso.freight.tracking.view;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.contoso.freight.tracking.shipment.ProofOfDelivery;
import com.contoso.freight.tracking.shipment.Shipment;
import com.contoso.freight.tracking.shipment.ShipmentStatus;
import com.contoso.freight.tracking.shipment.TrackingEvent;

public final class ShipmentViews {
    private final Clock clock;

    public ShipmentViews(Clock clock) {
        this.clock = clock;
    }

    public List<ShipmentSummary> summaries(List<Shipment> shipments, Set<Include> includes) {
        ArrayList<ShipmentSummary> rows = new ArrayList<>(shipments.size());
        for (Shipment shipment : shipments) {
            rows.add(summary(shipment, includes));
        }
        return rows;
    }

    public TrackingView tracking(Shipment shipment) {
        Instant estimate = DeliveryEstimates.estimate(shipment, clock.instant());
        return new TrackingView(
                shipment.trackingNumber(),
                shipment.status(),
                shipment.serviceLevel(),
                destination(shipment),
                estimate == null ? null : estimate.toString(),
                eventViews(shipment.events()));
    }

    /**
     * What the shipper's customer may see of the delivery capture.
     */
    public ProofOfDelivery customerProofOfDelivery(Shipment shipment) {
        ProofOfDelivery proof = shipment.proofOfDelivery();
        if (proof == null) {
            return null;
        }
        return proof.redactedForCustomer();
    }

    private ShipmentSummary summary(Shipment shipment, Set<Include> includes) {
        Instant now = clock.instant();
        Instant estimate = DeliveryEstimates.estimate(shipment, now);
        boolean late = shipment.status() == ShipmentStatus.DELIVERED
                ? shipment.lastEvent().at().isAfter(shipment.promisedBy())
                : estimate != null && estimate.isAfter(shipment.promisedBy());
        List<ShipmentSummary.EventView> events = includes.contains(Include.EVENTS)
                ? eventViews(shipment.events())
                : null;
        ShipmentSummary.PodSummary proof = includes.contains(Include.POD) ? proofOfDelivery(shipment) : null;
        return new ShipmentSummary(
                shipment.trackingNumber(),
                shipment.customerReference(),
                shipment.serviceLevel(),
                shipment.status(),
                shipment.originCity(),
                destination(shipment),
                shipment.createdAt().toString(),
                shipment.promisedBy().toString(),
                estimate == null ? null : estimate.toString(),
                late,
                shipment.weightKg(),
                shipment.pieces(),
                eventView(shipment.lastEvent()),
                events,
                proof);
    }

    private ShipmentSummary.PodSummary proofOfDelivery(Shipment shipment) {
        ProofOfDelivery proof = customerProofOfDelivery(shipment);
        if (proof == null) {
            return null;
        }
        String href = "/v1/accounts/" + shipment.accountId() + "/shipments/" + shipment.trackingNumber() + "/pod/photo";
        return new ShipmentSummary.PodSummary(
                proof.recipientName(),
                proof.capturedAt().toString(),
                proof.leftAt(),
                proof.hasSignature(),
                proof.hasPhoto(),
                href,
                proof.photoSha256());
    }

    private static String destination(Shipment shipment) {
        return shipment.destinationCity() + " " + shipment.destinationPostcode();
    }

    private static List<ShipmentSummary.EventView> eventViews(List<TrackingEvent> events) {
        ArrayList<ShipmentSummary.EventView> views = new ArrayList<>(events.size());
        for (TrackingEvent event : events) {
            views.add(eventView(event));
        }
        return List.copyOf(views);
    }

    private static ShipmentSummary.EventView eventView(TrackingEvent event) {
        return new ShipmentSummary.EventView(
                event.at().toString(),
                event.code(),
                event.facility(),
                event.description());
    }
}
