# Proof-of-delivery list rows

Portal 6.0 lists every shipment on the account and asks for a delivered-and-signed-by badge (`status=ALL&include=pod`). About 12,500 of the 16,000 shipments are delivered. Warmup and recovery use portal 5.14, which lists active shipments and does not ask for the badge. CPU stays near 0.05 cores the whole time. During the rollout the allocation rate rises from about 7 MB/s to about 18 MB/s. Latency stays flat. The JSON only carries metadata, so account-list responses grow by the badge fields and tracking responses stay the same size.

## Cause

`ProofOfDelivery` is immutable. Its public constructor clones the signature and the photo so those byte arrays never escape the instance, and the accessors return clones too. `redactedForCustomer()` is the customer view: surname reduced to an initial, driver id removed, coordinates rounded to three decimals. It returns `new ProofOfDelivery(...)`, so the constructor clones the media again.

The account list does not send the images. `ShipmentViews` still builds each badge by calling `customerProofOfDelivery()`, which calls `redactedForCustomer()`, and then reads the redacted name, the capture time, the drop-off location, and `hasSignature()` / `hasPhoto()`. Those flags only need the array lengths. Each delivered row still clones about 14 KiB of signature and photo, and the clones are discarded after the flags are read.

Portal 6 requests this for every row on the page. With `status=ALL`, most of the book is delivered, so most rows pay for that clone. Portal 5's active-only list has no proof of delivery and does not ask for `include=pod`, which is why warmup and recovery stay at the lower rate. The photo route is different: it returns `photoJpeg()` to the client, and that response should keep its own clone.

## Fix

Build the badge from the stored proof of delivery. Take the redacted name, the rounded coordinates, and the length checks from the instance the snapshot already holds, and do not construct a second `ProofOfDelivery` for the list. Alternatively, treat the media as immutable and let `redactedForCustomer()` share those arrays with the new instance.

Keep the cloning constructor, and the cloning `photoJpeg()` accessor, for the photo download. That is the path where the bytes leave the process.
