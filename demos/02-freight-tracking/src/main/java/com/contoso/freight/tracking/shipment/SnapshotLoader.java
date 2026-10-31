package com.contoso.freight.tracking.shipment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Rebuilds the read model from the nightly snapshot of the tracking event stream.
 * The snapshot is synthesised deterministically from a seed.
 */
public final class SnapshotLoader {
    private static final int MEDIA_VARIANTS = 64;
    private static final String[] CITIES = {
            "Seattle", "Portland", "San Jose", "Los Angeles", "Phoenix", "Denver",
            "Dallas", "Houston", "Chicago", "Minneapolis", "Kansas City", "Atlanta",
            "Miami", "Charlotte", "Nashville", "Columbus", "Detroit", "Boston",
            "Newark", "Philadelphia", "Washington", "Memphis", "Louisville", "Indianapolis"
    };
    private static final String[] HUBS = {
            "Memphis", "Louisville", "Indianapolis", "Atlanta", "Dallas", "Chicago",
            "Newark", "Los Angeles", "Oakland", "Denver", "Phoenix", "Columbus",
            "Kansas City", "Seattle"
    };
    private static final String[] GIVEN_NAMES = {
            "Maria", "James", "Wei", "Aisha", "Luis", "Olivia", "Noah", "Sofia",
            "Ethan", "Priya", "Jonah", "Elena", "Marcus", "Hannah", "Andre", "Grace"
    };
    private static final String[] SURNAMES = {
            "Gonzalez", "Patel", "Nguyen", "Brooks", "Kim", "Silva", "Cohen", "Rossi",
            "Nowak", "Ibrahim", "Chen", "Murphy", "Santos", "Berg", "Okonkwo", "Doyle"
    };
    private static final String[] LEFT_AT = {
            "FRONT_DOOR", "RECEPTION", "MAILROOM", "SIDE_DOOR", "HANDED_TO_RECIPIENT"
    };

    private final long seed;
    private final Clock clock;

    public SnapshotLoader(long seed, Clock clock) {
        this.seed = seed;
        this.clock = clock;
    }

    public ShipmentStore load(int accounts, int shipments, int retentionDays) {
        SplittableRandom random = new SplittableRandom(seed);
        byte[][] signatures = new byte[MEDIA_VARIANTS][];
        byte[][] photos = new byte[MEDIA_VARIANTS][];
        String[] digests = new String[MEDIA_VARIANTS];
        for (int variant = 0; variant < MEDIA_VARIANTS; variant++) {
            signatures[variant] = png(random, 2 * 1024 + random.nextInt(2 * 1024 + 1));
            photos[variant] = jpeg(random, 8 * 1024 + random.nextInt(8 * 1024 + 1));
            digests[variant] = hexPrefix(photos[variant]);
        }

        Instant now = clock.instant();
        long windowNanos = Duration.ofDays(retentionDays).toNanos();
        ServiceLevel[] levels = ServiceLevel.values();
        ArrayList<Shipment> loaded = new ArrayList<>(shipments);
        for (int i = 0; i < shipments; i++) {
            loaded.add(shipment(random, i, accounts, levels, now, windowNanos, signatures, photos, digests));
        }
        return new ShipmentStore(loaded);
    }

    private static Shipment shipment(
            SplittableRandom random,
            int index,
            int accounts,
            ServiceLevel[] levels,
            Instant now,
            long windowNanos,
            byte[][] signatures,
            byte[][] photos,
            String[] digests) {
        double unit = random.nextDouble();
        String accountId = String.format("ACC-%05d", 10_001 + (int) (accounts * unit * unit));
        ServiceLevel serviceLevel = levels[random.nextInt(levels.length)];
        Instant createdAt = now.minusNanos(random.nextLong(windowNanos));
        Instant promisedBy = createdAt.plus(serviceLevel.promise());
        double progress = Duration.between(createdAt, now).toNanos() / (double) serviceLevel.promise().toNanos();
        ShipmentStatus status = status(random, progress);

        int originIndex = random.nextInt(CITIES.length);
        int destinationIndex = random.nextInt(CITIES.length - 1);
        if (destinationIndex >= originIndex) {
            destinationIndex++;
        }
        String originCity = CITIES[originIndex];
        String destinationCity = CITIES[destinationIndex];
        String destinationPostcode = String.format("%05d", 10_000 + random.nextInt(90_000));
        String originHub = HUBS[random.nextInt(HUBS.length)];
        String destinationHub = HUBS[random.nextInt(HUBS.length)];
        double weightKg = Math.round((0.4 + random.nextDouble() * 28.0) * 100.0) / 100.0;
        int pieces = 1 + random.nextInt(4);
        String customerReference = String.format("PO-%08d", random.nextInt(100_000_000));
        List<TrackingEvent> events = events(
                random, status, createdAt, now, originCity, destinationCity, originHub, destinationHub);

        ProofOfDelivery proof = null;
        if (status == ShipmentStatus.DELIVERED) {
            String recipient = GIVEN_NAMES[random.nextInt(GIVEN_NAMES.length)] + " "
                    + SURNAMES[random.nextInt(SURNAMES.length)];
            String driverId = String.format("DRV-%04d", random.nextInt(10_000));
            String leftAt = LEFT_AT[random.nextInt(LEFT_AT.length)];
            double latitude = 25.0 + random.nextDouble() * 24.0;
            double longitude = -124.0 + random.nextDouble() * 54.0;
            int signature = random.nextInt(signatures.length);
            int photo = random.nextInt(photos.length);
            proof = new ProofOfDelivery(
                    recipient,
                    events.get(events.size() - 1).at(),
                    driverId,
                    leftAt,
                    latitude,
                    longitude,
                    signatures[signature],
                    photos[photo],
                    digests[photo]);
        }

        return new Shipment(
                String.format("CF%010d", 4_100_000_000L + index),
                accountId,
                customerReference,
                serviceLevel,
                originCity,
                destinationCity,
                destinationPostcode,
                createdAt,
                promisedBy,
                status,
                events,
                weightKg,
                pieces,
                proof);
    }

    private static ShipmentStatus status(SplittableRandom random, double progress) {
        if (random.nextInt(60) == 0) {
            return ShipmentStatus.EXCEPTION;
        }
        if (progress < 0.05) {
            return ShipmentStatus.LABEL_CREATED;
        }
        if (progress < 0.15) {
            return ShipmentStatus.PICKED_UP;
        }
        if (progress < 0.85) {
            return ShipmentStatus.IN_TRANSIT;
        }
        if (progress < 1.0) {
            return random.nextBoolean() ? ShipmentStatus.OUT_FOR_DELIVERY : ShipmentStatus.DELIVERED;
        }
        return ShipmentStatus.DELIVERED;
    }

    private static List<TrackingEvent> events(
            SplittableRandom random,
            ShipmentStatus status,
            Instant createdAt,
            Instant now,
            String originCity,
            String destinationCity,
            String originHub,
            String destinationHub) {
        List<Scan> scans = scans(status, originCity, destinationCity, originHub, destinationHub);
        long[] gapNanos = gaps(random, scans.size() - 1, Duration.between(createdAt, now).toNanos());
        ArrayList<TrackingEvent> events = new ArrayList<>(scans.size());
        Instant at = createdAt;
        for (int i = 0; i < scans.size(); i++) {
            if (i > 0) {
                at = at.plusNanos(gapNanos[i - 1]);
            }
            Scan scan = scans.get(i);
            events.add(new TrackingEvent(at, scan.code, scan.facility, scan.description));
        }
        return List.copyOf(events);
    }

    private static long[] gaps(SplittableRandom random, int count, long availableNanos) {
        long[] gapNanos = new long[count];
        if (count == 0) {
            return gapNanos;
        }
        long minGap = Duration.ofMinutes(30).toNanos();
        long maxGap = Duration.ofMinutes(270).toNanos();
        long sum = 0;
        for (int i = 0; i < count; i++) {
            gapNanos[i] = minGap + random.nextLong(maxGap - minGap + 1);
            sum += gapNanos[i];
        }
        if (sum <= availableNanos || availableNanos <= 0) {
            if (availableNanos <= 0) {
                for (int i = 0; i < count; i++) {
                    gapNanos[i] = 0;
                }
            }
            return gapNanos;
        }
        long scaled = 0;
        double scale = availableNanos / (double) sum;
        for (int i = 0; i < count; i++) {
            gapNanos[i] = (long) (gapNanos[i] * scale);
            scaled += gapNanos[i];
        }
        gapNanos[count - 1] += availableNanos - scaled;
        return gapNanos;
    }

    private static List<Scan> scans(
            ShipmentStatus status,
            String originCity,
            String destinationCity,
            String originHub,
            String destinationHub) {
        Scan label = new Scan("LBL", originCity, "Label created");
        Scan pickup = new Scan("PUP", originCity, "Picked up");
        Scan arriveOrigin = new Scan("ARR", originHub, "Arrived at facility");
        Scan departOrigin = new Scan("DEP", originHub, "Departed facility");
        Scan arriveDestination = new Scan("ARR", destinationHub, "Arrived at facility");
        Scan outForDelivery = new Scan("OFD", destinationCity, "Out for delivery");
        Scan delivered = new Scan("DLV", destinationCity, "Delivered");
        return switch (status) {
            case LABEL_CREATED -> List.of(label);
            case PICKED_UP -> List.of(label, pickup);
            case IN_TRANSIT -> List.of(label, pickup, arriveOrigin, departOrigin, arriveDestination);
            case OUT_FOR_DELIVERY -> List.of(
                    label, pickup, arriveOrigin, departOrigin, arriveDestination, outForDelivery);
            case DELIVERED -> List.of(
                    label, pickup, arriveOrigin, departOrigin, arriveDestination, outForDelivery, delivered);
            case EXCEPTION -> List.of(label, pickup, arriveOrigin, new Scan("EXC", originHub, "Shipment exception"));
        };
    }

    private static byte[] png(SplittableRandom random, int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        bytes[0] = (byte) 0x89;
        bytes[1] = 0x50;
        bytes[2] = 0x4E;
        bytes[3] = 0x47;
        bytes[4] = 0x0D;
        bytes[5] = 0x0A;
        bytes[6] = 0x1A;
        bytes[7] = 0x0A;
        return bytes;
    }

    private static byte[] jpeg(SplittableRandom random, int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    private static String hexPrefix(byte[] photo) {
        char[] hex = new char[64];
        for (int i = 0; i < 32; i++) {
            int value = photo[i] & 0xFF;
            hex[i * 2] = Character.forDigit(value >>> 4, 16);
            hex[i * 2 + 1] = Character.forDigit(value & 0x0F, 16);
        }
        return new String(hex);
    }

    private record Scan(String code, String facility, String description) {
    }
}
