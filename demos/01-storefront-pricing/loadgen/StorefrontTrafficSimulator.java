import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Storefront quote traffic. The mobile app 7.2 released to 90 % of shoppers
 * ('Smart Wallet': saved coupons auto-applied) is rolled back via feature flag.
 */
public final class StorefrontTrafficSimulator {
    private static final String[] CODES = {
            "WELCOME10",
            "SPRING-HOME",
            "PANTRY5",
            "BACK2SCHOOL",
            "GARDEN15",
            "PETPALS",
            "TECHWEEK",
            "BABY-BUNDLE",
            "OUTDOOR20",
            "KITCHEN-PRO",
            "BEAUTY8",
            "FREESHIP50",
            "LOYAL-GOLD",
            "STUDENT12",
            "MILITARY10",
            "APPONLY7",
            "BULK-SAVE",
            "CLEARANCE",
            "HOLIDAY-EARLY",
            "REFER-A-FRIEND",
            "BIRTHDAY",
            "SNACK-ATTACK",
            "AUTO-CARE",
            "TOY-DAYS"
    };
    private static final String[] TIERS = {"GUEST", "GUEST", "SILVER", "SILVER", "GOLD", "PLATINUM"};
    private static final int IN_FLIGHT_LIMIT = 512;
    private static final String[] PHASES = {"warmup", "rollout", "recovery"};

    private static volatile String phaseName = "warmup";

    private StorefrontTrafficSimulator() {
    }

    public static void main(String[] args) {
        Arguments arguments = Arguments.parse(args);
        SplittableRandom random = new SplittableRandom(42);
        ZipfSampler skuZipf = new ZipfSampler(arguments.products, 1.1);
        ZipfSampler storeZipf = new ZipfSampler(arguments.stores, 0.8);
        String[] skus = new String[arguments.products];
        for (int i = 0; i < skus.length; i++) {
            skus[i] = String.format("SKU-%05d", i);
        }
        String[] storeIds = new String[arguments.stores];
        for (int i = 0; i < storeIds.length; i++) {
            storeIds[i] = String.format("ST-%03d", i + 1);
        }

        ExecutorService httpWorkers = Executors.newFixedThreadPool(16, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .executor(httpWorkers)
                .build();
        URI quotes = URI.create(stripSlash(arguments.target) + "/v1/quotes");
        Semaphore inFlight = new Semaphore(IN_FLIGHT_LIMIT);
        AtomicLong sent = new AtomicLong();
        AtomicLong ok = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        AtomicLong dropped = new AtomicLong();

        int[] walletPercent = {0, arguments.walletShare, 0};
        long[] phaseEnds = new long[PHASES.length];
        long started = System.nanoTime();
        long cursor = started;
        for (int i = 0; i < PHASES.length; i++) {
            cursor += Duration.ofMinutes(phaseMinutes(PHASES[i], arguments)).toNanos() / arguments.timeScale;
            phaseEnds[i] = cursor;
        }

        ScheduledExecutorService stats = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "traffic-stats");
            thread.setDaemon(true);
            return thread;
        });
        stats.scheduleAtFixedRate(
                () -> logStats(sent.get(), ok.get(), failed.get(), dropped.get()),
                30,
                30,
                TimeUnit.SECONDS);

        long intervalNanos = 1_000_000_000L / arguments.rps;
        long nextTick = System.nanoTime();
        int phase = 0;
        long cartId = 1;
        phaseName = PHASES[0];
        logPhase(PHASES[0], walletPercent[0], phaseSeconds(0, started, phaseEnds), arguments.rps);
        while (phase < PHASES.length) {
            long now = System.nanoTime();
            if (now >= phaseEnds[phase]) {
                phase++;
                if (phase >= PHASES.length) {
                    break;
                }
                phaseName = PHASES[phase];
                logPhase(PHASES[phase], walletPercent[phase], phaseSeconds(phase, started, phaseEnds), arguments.rps);
                continue;
            }
            if (now < nextTick) {
                LockSupport.parkNanos(nextTick - now);
                if (Thread.interrupted()) {
                    break;
                }
                continue;
            }
            if (now - nextTick > intervalNanos) {
                nextTick = now;
            }
            nextTick += intervalNanos;
            dispatch(
                    client,
                    quotes,
                    inFlight,
                    sent,
                    ok,
                    failed,
                    dropped,
                    random,
                    skuZipf,
                    storeZipf,
                    skus,
                    storeIds,
                    walletPercent[phase],
                    "C-" + cartId++);
        }

        stats.shutdown();
        try {
            inFlight.tryAcquire(IN_FLIGHT_LIMIT, 15, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        logStats(sent.get(), ok.get(), failed.get(), dropped.get());
        System.out.flush();
        httpWorkers.shutdown();
    }

    private static void dispatch(
            HttpClient client,
            URI quotes,
            Semaphore inFlight,
            AtomicLong sent,
            AtomicLong ok,
            AtomicLong failed,
            AtomicLong dropped,
            SplittableRandom random,
            ZipfSampler skuZipf,
            ZipfSampler storeZipf,
            String[] skus,
            String[] storeIds,
            int walletPercent,
            String cartId) {
        if (!inFlight.tryAcquire()) {
            dropped.incrementAndGet();
            return;
        }
        String channel;
        List<String> coupons;
        int mobileShare = walletPercent > 0 ? walletPercent : 20;
        if (random.nextInt(100) < mobileShare) {
            if (walletPercent > 0) {
                channel = "ios-7.2";
                coupons = wallet(random);
            } else {
                channel = "android-6.9";
                coupons = singleCode(random, 15);
            }
        } else {
            channel = "web";
            coupons = singleCode(random, 25);
        }
        int lineCount = 1 + random.nextInt(6);
        int[] skuIndex = new int[lineCount];
        int[] quantities = new int[lineCount];
        for (int i = 0; i < lineCount; i++) {
            skuIndex[i] = skuZipf.sample(random);
            quantities[i] = random.nextInt(100) < 10 ? 1 + random.nextInt(12) : 1;
        }
        String body = cartJson(
                cartId,
                storeIds[storeZipf.sample(random)],
                TIERS[random.nextInt(TIERS.length)],
                coupons,
                skuIndex,
                quantities,
                skus);
        HttpRequest request = HttpRequest.newBuilder(quotes)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Client-Channel", channel)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        sent.incrementAndGet();
        try {
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
                try {
                    if (error != null || response.statusCode() >= 300) {
                        failed.incrementAndGet();
                    } else {
                        ok.incrementAndGet();
                    }
                } finally {
                    inFlight.release();
                }
            });
        } catch (RuntimeException exception) {
            failed.incrementAndGet();
            inFlight.release();
        }
    }

    private static List<String> wallet(SplittableRandom random) {
        String[] pool = new String[14];
        System.arraycopy(CODES, 0, pool, 0, pool.length);
        for (int i = pool.length - 1; i > 0; i--) {
            int swap = random.nextInt(i + 1);
            String held = pool[i];
            pool[i] = pool[swap];
            pool[swap] = held;
        }
        int count = 3 + random.nextInt(4);
        ArrayList<String> picked = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            picked.add(pool[i]);
        }
        if (random.nextInt(100) < 25) {
            picked.set(0, picked.get(0).toLowerCase(Locale.ROOT));
        }
        return picked;
    }

    private static List<String> singleCode(SplittableRandom random, int percent) {
        if (random.nextInt(100) >= percent) {
            return List.of();
        }
        return List.of(CODES[random.nextInt(8)]);
    }

    private static String cartJson(
            String cartId,
            String storeId,
            String tier,
            List<String> coupons,
            int[] skuIndex,
            int[] quantities,
            String[] skus) {
        StringBuilder json = new StringBuilder(256);
        json.append("{\"cartId\":\"").append(cartId)
                .append("\",\"storeId\":\"").append(storeId)
                .append("\",\"loyaltyTier\":\"").append(tier)
                .append("\",\"couponCodes\":[");
        for (int i = 0; i < coupons.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(coupons.get(i)).append('"');
        }
        json.append("],\"lines\":[");
        for (int i = 0; i < skuIndex.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"sku\":\"").append(skus[skuIndex[i]])
                    .append("\",\"quantity\":").append(quantities[i]).append('}');
        }
        json.append("]}");
        return json.toString();
    }

    private static int phaseMinutes(String phase, Arguments arguments) {
        return switch (phase) {
            case "warmup" -> arguments.warmupMin;
            case "rollout" -> arguments.rolloutMin;
            case "recovery" -> arguments.recoveryMin;
            default -> 0;
        };
    }

    private static long phaseSeconds(int phase, long started, long[] phaseEnds) {
        long begin = phase == 0 ? started : phaseEnds[phase - 1];
        return TimeUnit.NANOSECONDS.toSeconds(phaseEnds[phase] - begin);
    }

    private static void logPhase(String phase, int mobileWalletPercent, long durationSec, int rps) {
        System.out.println("{\"ts\":\"" + Instant.now() + "\",\"event\":\"phase\",\"phase\":\"" + phase
                + "\",\"mobileWalletPercent\":" + mobileWalletPercent
                + ",\"durationSec\":" + durationSec
                + ",\"rps\":" + rps + "}");
    }

    private static void logStats(long sent, long ok, long failed, long dropped) {
        System.out.println("{\"ts\":\"" + Instant.now() + "\",\"event\":\"stats\",\"phase\":\"" + phaseName
                + "\",\"sent\":" + sent
                + ",\"ok\":" + ok
                + ",\"failed\":" + failed
                + ",\"dropped\":" + dropped + "}");
    }

    private static String stripSlash(String target) {
        if (target.endsWith("/")) {
            return target.substring(0, target.length() - 1);
        }
        return target;
    }

    private static final class ZipfSampler {
        private final double[] cumulative;
        private final double total;

        private ZipfSampler(int size, double exponent) {
            cumulative = new double[size];
            double sum = 0;
            for (int rank = 0; rank < size; rank++) {
                sum += Math.pow(rank + 1, -exponent);
                cumulative[rank] = sum;
            }
            total = sum;
        }

        private int sample(SplittableRandom random) {
            double draw = random.nextDouble() * total;
            int low = 0;
            int high = cumulative.length - 1;
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (cumulative[mid] < draw) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }
    }

    private static final class Arguments {
        private final String target;
        private final int rps;
        private final int timeScale;
        private final int products;
        private final int stores;
        private final int walletShare;
        private final int warmupMin;
        private final int rolloutMin;
        private final int recoveryMin;

        private Arguments(
                String target,
                int rps,
                int timeScale,
                int products,
                int stores,
                int walletShare,
                int warmupMin,
                int rolloutMin,
                int recoveryMin) {
            this.target = target;
            this.rps = rps;
            this.timeScale = timeScale;
            this.products = products;
            this.stores = stores;
            this.walletShare = walletShare;
            this.warmupMin = warmupMin;
            this.rolloutMin = rolloutMin;
            this.recoveryMin = recoveryMin;
        }

        private static Arguments parse(String[] args) {
            String target = env("TARGET", "http://127.0.0.1:8080");
            int rps = envInt("RPS", 120);
            int timeScale = envInt("TIME_SCALE", 1);
            int products = envInt("PRODUCTS", 5_000);
            int stores = envInt("STORES", 40);
            int walletShare = envInt("WALLET_SHARE", 90);
            int warmupMin = envInt("WARMUP_MIN", 5);
            int rolloutMin = envInt("ROLLOUT_MIN", 4);
            int recoveryMin = envInt("RECOVERY_MIN", 2);
            for (int i = 0; i < args.length; i++) {
                String flag = args[i];
                if ("--help".equals(flag)) {
                    usage();
                    System.exit(0);
                }
                if (i + 1 >= args.length) {
                    usage();
                    System.exit(2);
                }
                String value = args[++i];
                switch (flag) {
                    case "--target" -> target = value;
                    case "--rps" -> rps = Integer.parseInt(value);
                    case "--time-scale" -> timeScale = Integer.parseInt(value);
                    case "--products" -> products = Integer.parseInt(value);
                    case "--stores" -> stores = Integer.parseInt(value);
                    case "--wallet-share" -> walletShare = Integer.parseInt(value);
                    case "--warmup-min" -> warmupMin = Integer.parseInt(value);
                    case "--rollout-min" -> rolloutMin = Integer.parseInt(value);
                    case "--recovery-min" -> recoveryMin = Integer.parseInt(value);
                    default -> {
                        usage();
                        System.exit(2);
                    }
                }
            }
            if (rps < 1 || timeScale < 1 || products < 1 || stores < 1 || warmupMin < 0 || rolloutMin < 0
                    || recoveryMin < 0 || walletShare < 0 || walletShare > 100) {
                usage();
                System.exit(2);
            }
            return new Arguments(target, rps, timeScale, products, stores, walletShare, warmupMin, rolloutMin, recoveryMin);
        }

        private static void usage() {
            System.err.println("usage: StorefrontTrafficSimulator [--target URL] [--rps N] [--time-scale N]"
                    + " [--products N] [--stores N] [--wallet-share N] [--warmup-min N] [--rollout-min N]"
                    + " [--recovery-min N]");
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return value.trim();
        }

        private static int envInt(String name, int fallback) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return Integer.parseInt(value.trim());
        }
    }
}
