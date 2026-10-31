import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Customer portal 6.0 released to 90 % of portal users: the Shipments tab now lists
 * every shipment (not only active ones) with a 'delivered + signed by' badge.
 * Rolled back via feature flag.
 */
public final class FreightTrafficSimulator {
    private static final int IN_FLIGHT_LIMIT = 512;
    private static final String[] PHASES = {"warmup", "rollout", "recovery"};
    private static final int[] PAGE_OFFSETS = {25, 50, 75};

    private static volatile String phaseName = "warmup";

    private FreightTrafficSimulator() {
    }

    public static void main(String[] args) {
        Arguments arguments = Arguments.parse(args);
        SplittableRandom random = new SplittableRandom(7);
        ExecutorService httpWorkers = Executors.newFixedThreadPool(16, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .executor(httpWorkers)
                .build();
        String target = stripSlash(arguments.target);
        Semaphore inFlight = new Semaphore(IN_FLIGHT_LIMIT);
        AtomicLong sent = new AtomicLong();
        AtomicLong ok = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        AtomicLong dropped = new AtomicLong();

        int[] portal6Share = {0, arguments.portal6Share, 0};
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
        phaseName = PHASES[0];
        logPhase(PHASES[0], portal6Share[0], phaseSeconds(0, started, phaseEnds), arguments.rps);
        while (phase < PHASES.length) {
            long now = System.nanoTime();
            if (now >= phaseEnds[phase]) {
                phase++;
                if (phase >= PHASES.length) {
                    break;
                }
                phaseName = PHASES[phase];
                logPhase(PHASES[phase], portal6Share[phase], phaseSeconds(phase, started, phaseEnds), arguments.rps);
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
            dispatch(client, target, inFlight, sent, ok, failed, dropped, random, arguments, portal6Share[phase]);
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
            String target,
            Semaphore inFlight,
            AtomicLong sent,
            AtomicLong ok,
            AtomicLong failed,
            AtomicLong dropped,
            SplittableRandom random,
            Arguments arguments,
            int portal6Share) {
        if (!inFlight.tryAcquire()) {
            dropped.incrementAndGet();
            return;
        }
        int draw = random.nextInt(100);
        String clientApp;
        String path;
        if (draw < 38) {
            clientApp = "tracking-page/2026.10";
            path = "/v1/track/" + trackingNumber(random, arguments.shipments);
        } else if (draw < 50) {
            clientApp = random.nextBoolean() ? "mobile/ios-9.3" : "mobile/android-9.3";
            path = "/v1/track/" + trackingNumber(random, arguments.shipments);
        } else if (draw < 88) {
            String account = accountId(random, arguments.accounts);
            String cursor = pageCursor(random);
            if (random.nextInt(100) < portal6Share) {
                clientApp = "portal/6.0.2";
                path = "/v1/accounts/" + account + "/shipments?status=ALL&limit=25&include=pod" + cursor;
            } else {
                clientApp = "portal/5.14.1";
                String include = random.nextInt(100) < 10 ? "&include=events" : "";
                path = "/v1/accounts/" + account + "/shipments?status=ACTIVE&limit=25" + include + cursor;
            }
        } else {
            clientApp = "erp-connector/2.3.0";
            String account = accountId(random, arguments.accounts);
            path = "/v1/accounts/" + account + "/shipments?status=IN_TRANSIT&limit=50&include=events";
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(target + path))
                .timeout(Duration.ofSeconds(10))
                .header("X-Client-App", clientApp)
                .GET()
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

    private static String trackingNumber(SplittableRandom random, int shipments) {
        return String.format("CF%010d", 4_100_000_000L + random.nextInt(shipments));
    }

    private static String accountId(SplittableRandom random, int accounts) {
        double unit = random.nextDouble();
        return String.format("ACC-%05d", 10_001 + (int) (accounts * unit * unit));
    }

    private static String pageCursor(SplittableRandom random) {
        if (random.nextInt(6) != 0) {
            return "";
        }
        int offset = PAGE_OFFSETS[random.nextInt(PAGE_OFFSETS.length)];
        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("o:" + offset).getBytes(StandardCharsets.UTF_8));
        return "&cursor=" + encoded;
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

    private static void logPhase(String phase, int portal6Share, long durationSec, int rps) {
        System.out.println("{\"ts\":\"" + Instant.now() + "\",\"event\":\"phase\",\"phase\":\"" + phase
                + "\",\"portal6Share\":" + portal6Share
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

    private static final class Arguments {
        private final String target;
        private final int rps;
        private final int timeScale;
        private final int accounts;
        private final int shipments;
        private final int portal6Share;
        private final int warmupMin;
        private final int rolloutMin;
        private final int recoveryMin;

        private Arguments(
                String target,
                int rps,
                int timeScale,
                int accounts,
                int shipments,
                int portal6Share,
                int warmupMin,
                int rolloutMin,
                int recoveryMin) {
            this.target = target;
            this.rps = rps;
            this.timeScale = timeScale;
            this.accounts = accounts;
            this.shipments = shipments;
            this.portal6Share = portal6Share;
            this.warmupMin = warmupMin;
            this.rolloutMin = rolloutMin;
            this.recoveryMin = recoveryMin;
        }

        private static Arguments parse(String[] args) {
            String target = env("TARGET", "http://127.0.0.1:8080");
            int rps = envInt("RPS", 120);
            int timeScale = envInt("TIME_SCALE", 1);
            int accounts = envInt("ACCOUNTS", 400);
            int shipments = envInt("SHIPMENTS", 16_000);
            int portal6Share = envInt("PORTAL6_SHARE", 90);
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
                    case "--accounts" -> accounts = Integer.parseInt(value);
                    case "--shipments" -> shipments = Integer.parseInt(value);
                    case "--portal6-share" -> portal6Share = Integer.parseInt(value);
                    case "--warmup-min" -> warmupMin = Integer.parseInt(value);
                    case "--rollout-min" -> rolloutMin = Integer.parseInt(value);
                    case "--recovery-min" -> recoveryMin = Integer.parseInt(value);
                    default -> {
                        usage();
                        System.exit(2);
                    }
                }
            }
            if (rps < 1 || timeScale < 1 || accounts < 1 || shipments < 1 || warmupMin < 0 || rolloutMin < 0
                    || recoveryMin < 0 || portal6Share < 0 || portal6Share > 100) {
                usage();
                System.exit(2);
            }
            return new Arguments(
                    target, rps, timeScale, accounts, shipments, portal6Share, warmupMin, rolloutMin, recoveryMin);
        }

        private static void usage() {
            System.err.println("usage: FreightTrafficSimulator [--target URL] [--rps N] [--time-scale N]"
                    + " [--accounts N] [--shipments N] [--portal6-share N] [--warmup-min N] [--rollout-min N]"
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
