# Storefront pricing

Prices a Northwind cart. The service loads a merchandising snapshot and a deals-sync promotion book, adjusts each line for the store price zone, loyalty tier, coupons, and weekday, then applies multi-buy breaks and sales tax.

Smart Wallet is the mobile 7.2 behavior that auto-applies a shopper's saved coupons. The traffic profile below releases it to 90% of shoppers and later turns it off with the feature flag.

## Endpoints

| Method | Path | Body |
| --- | --- | --- |
| `POST` | `/v1/quotes` | Quote request JSON. Header `X-Client-Channel` (default `unknown`) is a label on `pricing.quote.duration`. |
| `GET` | `/health` | `{"status":"UP"}` |
| `GET` | `/metrics` | Prometheus text, including `cache_gets_total{cache="line_price"}` and `pricing_quote_duration_seconds`. |

`POST /v1/quotes` returns 422 for an unknown store or SKU, and 400 for a malformed body.

Quote money fields are integer cents:

| Field | Meaning |
| --- | --- |
| `unitPriceCents` | Price-zone unit price before promotions. |
| `unitDiscountCents` | Promotional discount per unit, capped at 40% of the zone price. |
| `lineTotalCents` | Extended price after that discount and multi-buy (6+ units 5% off, 12+ units 10% off). |
| `promotionId` | Winning non-stackable promotion, or `-1` when the line has only stackable promotions or none. |
| `subtotalCents` | Sum of line totals, before tax. |
| `discountCents` | Zone-price merchandise minus `subtotalCents`. |
| `taxCents` | `subtotalCents` times the store sales-tax rate. |
| `totalCents` | `subtotalCents` plus tax. |

## Configuration

| Variable | Default | Role |
| --- | --- | --- |
| `PORT` | `8080` | Listen port. |
| `WORKER_THREADS` | `32` | Request worker threads. |
| `LINE_PRICE_CACHE_SIZE` | `200000` | Maximum cached line prices. |
| `PROMOTION_COUNT` | `120000` | Promotions materialised from `DEALS_SYNC_ID`. |
| `PRODUCT_COUNT` | `5000` | SKUs in the snapshot (`SKU-00000` …). |
| `STORE_COUNT` | `40` | Stores (`ST-001` …). |
| `CATALOG_SNAPSHOT_SEED` | `20261031` | Merchandising snapshot seed. |
| `DEALS_SYNC_ID` | `7741` | Promotion book seed. |
| `JAVA_TOOL_OPTIONS` | | Set in the deployment to `-XX:+ExitOnOutOfMemoryError`. |
| `JAVA_OPTS` | | Set in the deployment to `-Xms512m -Xmx512m -XX:+UseZGC -XX:+AlwaysPreTouch -XX:-ZProactive`. |
| `MALLOC_ARENA_MAX` | `2` | Also set in the image. |
| `OTEL_ENABLED` | `false` | `true` attaches the OpenTelemetry Java agent. Traces and metrics export OTLP; logs stay off. |

The traffic simulator reads the same names as its flags (`TIME_SCALE`, `RPS`, `TARGET`, `PRODUCTS`, `STORES`, `WALLET_SHARE`, `WARMUP_MIN`, `ROLLOUT_MIN`, `RECOVERY_MIN`). A flag overrides the environment.

## Traffic profile

Defaults are 11 minutes of open-loop traffic at 120 requests per second. That fits a live session of 10–15 minutes, including image pull and process start. `--time-scale` divides each phase length (2 runs the same profile in about 5.5 minutes).

| Phase | Default | Mobile share | Clients | Coupons |
| --- | --- | --- | --- | --- |
| `warmup` | 5 min | 20% | `android-6.9`, otherwise `web` | None, or one published code on some carts. |
| `rollout` | 4 min | 90% | `ios-7.2` for mobile, otherwise `web` | Smart Wallet attaches 3–6 saved codes, most-recently-used order. |
| `recovery` | 2 min | 20% | `android-6.9`, otherwise `web` | Same mix as warmup. The feature flag is off. |

SKU draws are Zipf with exponent 1.1. Store draws are Zipf with exponent 0.8. The simulator and the service must use the same product and store counts. JSON lines on stdout mark each phase and print `sent`, `ok`, `failed`, and `dropped` every 30 seconds.

## Run

Local:

```bash
mvn -B package
java -Xms512m -Xmx512m -XX:+UseZGC -XX:+AlwaysPreTouch -XX:-ZProactive -jar target/storefront-pricing.jar
javac --release 25 -d /tmp/storefront-loadgen loadgen/StorefrontTrafficSimulator.java
java -cp /tmp/storefront-loadgen StorefrontTrafficSimulator --target http://127.0.0.1:8080 --rps 120
```

Image and cluster, from this directory:

```bash
scripts/build-push.sh
kubectl apply -k k8s
kubectl -n northwind-pricing logs -f job/storefront-traffic
```

`PUSH=false scripts/build-push.sh` builds the manifest and does not push. `BUILDER=buildx` uses `docker buildx` instead of podman. The image is `quay.io/bharathappali/jafra-demo:01-storefront-pricing`.

```bash
curl -s http://127.0.0.1:8080/health
curl -s http://127.0.0.1:8080/v1/quotes \
  -H 'content-type: application/json' \
  -H 'X-Client-Channel: web' \
  -d '{"cartId":"C-1","storeId":"ST-001","loyaltyTier":"GOLD","couponCodes":["WELCOME10"],"lines":[{"sku":"SKU-00001","quantity":2}]}'
```

## What Jafra should show

Watch `cpu_jump` on the `pricing` container.

| Window | CPU | `line_price` hit ratio | Allocation rate |
| --- | --- | --- | --- |
| Warmup | about 0.65 cores | about 0.5–0.6 | baseline |
| Rollout | about 1.3 cores | about 0.1 | about the same as warmup |
| Recovery | back toward warmup | back toward warmup | about the same |

Hit ratio is `cache_gets_total{cache="line_price",result="hit"}` over hits plus misses. Quote latency moves with CPU. The rollout is long enough for `cpu_jump` to fire; recovery returns the process toward the warmup shape. A fast workstation shows the same hit-ratio break with a smaller core step. The core counts above match a modest node, where walking the promotion book costs more.

### Why `jafra.io/loop` is 10s

`cpu_jump` needs 12 JFR-chunk CPU values inside the analyzer's last 120 windows. Each loop adds `loop / 5s` profiler windows plus one JFR window. With `jafra.io/chunktime: 5s`, a 10s loop is 2 profiler windows + 1 JFR window. Twelve JFR-chunk values then occupy 12 × 3 = 36 windows, still inside the 120-window horizon, and they arrive in two minutes. The rollout lasts four minutes, so those samples land while CPU is elevated. A longer loop would push the 12th JFR-chunk value outside that horizon before the detector can use it.

### Why the memory settings look like this

`AlwaysPreTouch` commits the 512 MiB heap at startup. `-XX:-ZProactive` keeps ZGC from handing heap pages back on its own. `MALLOC_ARENA_MAX=2` caps glibc malloc arenas. The memory limit is 2 GiB and there is no CPU limit. Together they keep a young JVM's RSS slope under `native_growth`'s line of 10% of the limit per hour.

### `error_thrown` at startup

Binding processor metrics probes `com.ibm.lang.management.OperatingSystemMXBean`. That class is absent on HotSpot, and the JVM records the caught `ClassNotFoundException`. JFR also records linkage exceptions while the runtime initializes. Jafra can show an `error_thrown` companion from those startup throws. It is separate from pricing traffic. Use `cpu_jump` for this service.

Spoiler: [ANSWER.md](ANSWER.md).
