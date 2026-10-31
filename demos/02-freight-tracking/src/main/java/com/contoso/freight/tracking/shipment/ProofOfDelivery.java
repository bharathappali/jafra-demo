package com.contoso.freight.tracking.shipment;

import java.time.Instant;
import java.util.Objects;

/**
 * Delivery capture uploaded by the driver app. Immutable; the byte arrays never leak out.
 */
public final class ProofOfDelivery {
    private final String recipientName;
    private final Instant capturedAt;
    private final String driverId;
    private final String leftAt;
    private final double latitude;
    private final double longitude;
    private final byte[] signaturePng;
    private final byte[] photoJpeg;
    private final String photoSha256;

    public ProofOfDelivery(
            String recipientName,
            Instant capturedAt,
            String driverId,
            String leftAt,
            double latitude,
            double longitude,
            byte[] signaturePng,
            byte[] photoJpeg,
            String photoSha256) {
        this.recipientName = Objects.requireNonNull(recipientName, "recipientName");
        this.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        this.driverId = driverId;
        this.leftAt = leftAt;
        this.latitude = latitude;
        this.longitude = longitude;
        this.signaturePng = signaturePng == null ? new byte[0] : signaturePng.clone();
        this.photoJpeg = photoJpeg == null ? new byte[0] : photoJpeg.clone();
        this.photoSha256 = photoSha256;
    }

    public String recipientName() {
        return recipientName;
    }

    public Instant capturedAt() {
        return capturedAt;
    }

    public String driverId() {
        return driverId;
    }

    public String leftAt() {
        return leftAt;
    }

    public double latitude() {
        return latitude;
    }

    public double longitude() {
        return longitude;
    }

    public byte[] signaturePng() {
        return signaturePng.clone();
    }

    public byte[] photoJpeg() {
        return photoJpeg.clone();
    }

    public String photoSha256() {
        return photoSha256;
    }

    public boolean hasSignature() {
        return signaturePng.length > 0;
    }

    public boolean hasPhoto() {
        return photoJpeg.length > 0;
    }

    /**
     * The view a shipper's customer is allowed to see: recipient surname reduced to
     * an initial, no driver id, location rounded to roughly a street block.
     */
    public ProofOfDelivery redactedForCustomer() {
        return new ProofOfDelivery(
                PersonNames.initialOnly(recipientName),
                capturedAt,
                null,
                leftAt,
                round3(latitude),
                round3(longitude),
                signaturePng,
                photoJpeg,
                photoSha256);
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
