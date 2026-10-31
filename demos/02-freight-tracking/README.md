# Shipment tracking

Tracks Contoso Freight parcels for shippers and for the public tracking page. The service rebuilds its read model from the nightly tracking-event snapshot: one row per shipment, scans in order, and a proof of delivery once the driver has captured the drop-off.

Portal 6.0 is the shipper-portal release that lists every shipment on the account, not only the open ones, and shows a delivered-and-signed-by badge on the Shipments tab. The traffic profile below releases it to 90% of portal users and later turns it off with the feature flag.

## Endpoints

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/v1/accounts/{accountId}/shipments` | Account shipment list. Header `X-Client-App` (default `unknown`) is a label on `tracking_http_requests`. |
| `GET` | `/v1/accounts/{accountId}/shipments/{trackingNumber}/pod/photo` | Delivery photo, `image/jpeg`, `Cache-Control: private, max-age=86400`. |
| `GET` | `/v1/track/{trackingNumber}` | Public tracking timeline. No delivery-capture fields. |
| `GET` | `/health` | `{"status":"UP"}` |
| `GET` | `/metrics` | Prometheus text, including `tracking_http_requests` and `tracking_http_response_bytes`. |

`GET /v1/accounts/{accountId}/shipments` query:

| Parameter | Default | Meaning |
| --- | --- | --- |
| `status` | `ACTIVE` | `ACTIVE` (also a blank value) keeps shipments that are not delivered. `ALL` keeps every status. A status name keeps that status. Anything else is 400. |
| `limit` | `25` | Page size, clamped to `1..MAX_PAGE_SIZE`. |
| `cursor` | | URL-safe base64 of `o:<offset>`. A bad cursor is 400. |
| `include` | | Comma-separated `events` and `pod` (`proof_of_delivery` is accepted for `pod`). |

The list body is `{accountId, items, nextCursor}`. `nextCursor` is omitted when the page is the last one. Each item carries the latest scan. `events` is present only when `include` contains `events`. `proofOfDelivery` is present only when `include` contains `pod` and the shipment has been delivered. Timestamps are ISO-8601. `destination` is `city postcode`. An unknown account or tracking number is 404. A method other than `GET` is 405.

The customer badge is the redacted delivery capture: surname reduced to an initial, no driver id, coordinates rounded to three decimals, and whether a signature and a photo are on file. `photoHref` points at the photo route above.

## Configuration

| Variable | Default | Role |
| --- | --- | --- |
| `PORT` | `8080` | Listen port. |
| `WORKER_THREADS` | `32` | Request worker threads. |
| `ACCOUNT_COUNT` | `400` | Shipper accounts in the snapshot (`ACC-10001` …). |
| `SHIPMENT_COUNT` | `16000` | Shipments in the snapshot (`CF4100000000` …). |
| `RETENTION_DAYS` | `10` | How far back the snapshot reaches. |
| `MAX_PAGE_SIZE` | `100` | Upper bound for `limit`. |
| `SNAPSHOT_SEED` | `20261031` | Nightly snapshot seed. |
| `JAVA_TOOL_OPTIONS` | | Set in the deployment to `-XX:+ExitOnOutOfMemoryError`. |
| `JAVA_OPTS` | | Set in the deployment to `-Xms1g -Xmx1g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:-ZProactive`. |
| `MALLOC_ARENA_MAX` | `2` | Also set in the image. |
| `OTEL_ENABLED` | `false` | `true` attaches the OpenTelemetry Java agent. Traces and metrics export OTLP; logs stay off. |

The traffic simulator reads the same names as its flags (`TARGET`, `RPS`, `TIME_SCALE`, `ACCOUNTS`, `SHIPMENTS`, `PORTAL6_SHARE`, `WARMUP_MIN`, `ROLLOUT_MIN`, `RECOVERY_MIN`). A flag overrides the environment. The simulator and the service must use the same account count and shipment count.

## Traffic profile

Defaults are 11 minutes of open-loop traffic at 120 requests per second. That fits a live session of 10–15 minutes, including image pull and process start. `--time-scale` divides each phase length (2 runs the same profile in about 5.5 minutes).

Every request sends `X-Client-App`. The mix below is the same in every phase. Only the portal version changes.

| Share | Client | Request |
| --- | --- | --- |
| 38% | `tracking-page/2026.10` | `GET /v1/track/{trackingNumber}` |
| 12% | `mobile/ios-9.3` or `mobile/android-9.3` | `GET /v1/track/{trackingNumber}` |
| 38% | customer portal, account `ACC-%05d` drawn with the same account distribution as the snapshot | See the phase table. One request in six adds a cursor for offset 25, 50, or 75. |
| 12% | `erp-connector/2.3.0` | `?status=IN_TRANSIT&limit=50&include=events` |

| Phase | Default | Portal 6 share | Portal request |
| --- | --- | --- | --- |
| `warmup` | 5 min | 0 | `portal/5.14.1`, `status=ACTIVE`, `limit=25`. 10% of these also send `include=events`. |
| `rollout` | 4 min | 90 | 90% `portal/6.0.2`, `status=ALL`, `limit=25`, `include=pod`. The other 10% stay on portal 5.14.1. |
| `recovery` | 2 min | 0 | Same mix as warmup. The feature flag is off. |

JSON lines on stdout mark each phase and print `sent`, `ok`, `failed`, and `dropped` every 30 seconds. In-flight requests are capped at 512; further attempts count as dropped.

## Run

Local:

```bash
mvn -B package
java -Xms1g -Xmx1g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:-ZProactive -jar target/shipment-tracking.jar
javac --release 25 -d /tmp/freight-loadgen loadgen/FreightTrafficSimulator.java
java -cp /tmp/freight-loadgen FreightTrafficSimulator --target http://127.0.0.1:8080 --rps 120
```

Image and cluster, from this directory:

```bash
scripts/build-push.sh
kubectl apply -k k8s
kubectl -n contoso-freight logs -f job/freight-traffic
```

`PUSH=false scripts/build-push.sh` builds the manifest and does not push. `BUILDER=buildx` uses `docker buildx` instead of podman. The image is `quay.io/bharathappali/jafra-demo:02-freight-tracking`.

```bash
curl -s http://127.0.0.1:8080/health
curl -s http://127.0.0.1:8080/v1/track/CF4100000000
curl -s 'http://127.0.0.1:8080/v1/accounts/ACC-10001/shipments?status=ALL&limit=25&include=pod' \
  -H 'X-Client-App: portal/6.0.2'
```

## What Jafra should show

Watch `alloc_spike` on the `tracking` container. CPU stays near 0.05 cores at 120 requests per second in every phase. Latency stays flat. Tracking responses stay the same size. Account-list responses grow by the badge fields, not by the photo.

| Window | CPU | Allocation rate |
| --- | --- | --- |
| Warmup | about 0.05 cores | baseline, about 7 MB/s |
| Rollout | about 0.05 cores | about 2.5× warmup, about 18 MB/s |
| Recovery | about 0.05 cores | back to warmup |

Jafra's `alloc.rate.bytes_per_s` is the series to watch. `jvm_gc_memory_allocated_bytes_total` moves with it, in steps, because that counter advances when ZGC records a change in the young-generation pool. With a 1 GiB heap those steps are several seconds apart.

The rollout is long enough for `alloc_spike` to fire. Recovery returns the process to the warmup allocation rate.

### Why `jafra.io/loop` is 10s

`alloc.rate.bytes_per_s` comes from the JFR periodic event `jdk.ThreadAllocationStatistics`. That event is recorded once per profiling loop, so each loop contributes one allocation-rate value. The detector needs 12 of those values inside its last 120 windows.

With `jafra.io/chunktime: 5s`, a 10s loop is two profiler windows plus one JFR window. Twelve allocation-rate values then occupy 12 × 3 = 36 windows, still inside the 120-window horizon, and they cover two minutes. The rollout lasts four minutes, so those samples are taken while the rate is elevated. A longer loop would push the 12th value outside that horizon before the detector can use it.

### Why the memory settings look like this

`AlwaysPreTouch` commits the 1 GiB heap at startup. `-XX:-ZProactive` keeps ZGC from handing heap pages back on its own. `MALLOC_ARENA_MAX=2` caps glibc malloc arenas. The memory limit is 2 GiB and there is no CPU limit. Together they keep a young JVM's RSS slope under `native_growth`'s line of 10% of the limit per hour.

### `error_thrown` at startup

Binding processor metrics probes `com.ibm.lang.management.OperatingSystemMXBean`. That class is absent on HotSpot, and the JVM records the caught `ClassNotFoundException`. JFR also records linkage exceptions while the runtime initializes. Jafra can show an `error_thrown` companion from those startup throws. It is separate from tracking traffic. Use `alloc_spike` for this service.

Spoiler: [ANSWER.md](ANSWER.md).
