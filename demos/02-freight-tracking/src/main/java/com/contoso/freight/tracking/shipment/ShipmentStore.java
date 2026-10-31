package com.contoso.freight.tracking.shipment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class ShipmentStore {
    private final Map<String, Shipment> tracked;
    private final Map<String, List<Shipment>> byAccount;
    private final int deliveredCount;

    public ShipmentStore(List<Shipment> shipments) {
        Map<String, Shipment> tracked = HashMap.newHashMap(shipments.size());
        Map<String, List<Shipment>> grouped = new HashMap<>();
        int delivered = 0;
        for (Shipment shipment : shipments) {
            tracked.put(shipment.trackingNumber(), shipment);
            grouped.computeIfAbsent(shipment.accountId(), ignored -> new ArrayList<>()).add(shipment);
            if (shipment.status() == ShipmentStatus.DELIVERED) {
                delivered++;
            }
        }
        Map<String, List<Shipment>> frozen = HashMap.newHashMap(grouped.size());
        Comparator<Shipment> newestFirst = Comparator.comparing(Shipment::createdAt)
                .reversed()
                .thenComparing(Shipment::trackingNumber);
        for (Map.Entry<String, List<Shipment>> entry : grouped.entrySet()) {
            entry.getValue().sort(newestFirst);
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.tracked = Map.copyOf(tracked);
        this.byAccount = Map.copyOf(frozen);
        this.deliveredCount = delivered;
    }

    public Shipment byTrackingNumber(String trackingNumber) {
        return tracked.get(trackingNumber);
    }

    public boolean knowsAccount(String accountId) {
        return byAccount.containsKey(accountId);
    }

    public Page page(String accountId, Predicate<Shipment> filter, int offset, int limit) {
        List<Shipment> shipments = byAccount.get(accountId);
        if (shipments == null || shipments.isEmpty() || limit < 1 || offset < 0) {
            return new Page(List.of(), -1);
        }
        ArrayList<Shipment> items = new ArrayList<>(Math.min(limit, shipments.size()));
        int skipped = 0;
        boolean more = false;
        for (Shipment shipment : shipments) {
            if (!filter.test(shipment)) {
                continue;
            }
            if (skipped < offset) {
                skipped++;
                continue;
            }
            if (items.size() == limit) {
                more = true;
                break;
            }
            items.add(shipment);
        }
        int nextOffset = more ? offset + items.size() : -1;
        return new Page(List.copyOf(items), nextOffset);
    }

    public int size() {
        return tracked.size();
    }

    public int accountCount() {
        return byAccount.size();
    }

    public int deliveredCount() {
        return deliveredCount;
    }

    public record Page(List<Shipment> items, int nextOffset) {
    }
}
