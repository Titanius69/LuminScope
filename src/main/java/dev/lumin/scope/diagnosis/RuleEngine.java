package dev.lumin.scope.diagnosis;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.lumin.scope.config.ScopeConfig;
import dev.lumin.scope.metrics.EventLoopProbe;
import dev.lumin.scope.metrics.Histogram;
import dev.lumin.scope.metrics.JvmMetrics;
import dev.lumin.scope.metrics.LatencyTracker;
import dev.lumin.scope.watchdog.BlockingCallDetector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns metrics into conclusions.
 *
 * <p>No machine learning, and none is needed: a few dozen carefully chosen rules
 * cover the overwhelming majority of real incidents on a Minecraft proxy,
 * because the same handful of things go wrong over and over. The value is in
 * combining signals — high player latency plus healthy backend latency plus a
 * healthy proxy points somewhere very specific, and no single metric says it.
 *
 * <p>Every rule clears with hysteresis: a finding stays until the value drops
 * well below the threshold that raised it. Without that, a metric hovering at
 * the line produces an alert every window and people stop reading them.
 */
public final class RuleEngine {

    /** A finding clears only once the value falls to this fraction of its threshold. */
    private static final double CLEAR_RATIO = 0.7D;

    /** Below this many samples a percentile is not evidence of anything. */
    private static final long MIN_SAMPLES = 20L;

    private final ProxyServer proxy;
    private final Set<String> active = new HashSet<>();

    public RuleEngine(ProxyServer proxy) {
        this.proxy = proxy;
    }

    /** Everything the rules read, gathered once so a run sees a consistent picture. */
    public record Context(ScopeConfig config,
                          EventLoopProbe eventLoop,
                          JvmMetrics jvm,
                          LatencyTracker latency,
                          BlockingCallDetector blocking,
                          int onlinePlayers) {
    }

    public List<Finding> evaluate(Context context) {
        List<Finding> findings = new ArrayList<>();
        ScopeConfig.Thresholds limits = context.config().thresholds();

        // A proxy that started two minutes ago has cold JIT, an unwarmed heap and
        // almost no samples. Reporting on it produces nothing but false alarms.
        if (context.jvm().uptimeMillis() < 120_000L) {
            active.clear();
            return List.of(Finding.info("warming-up", "Still collecting data",
                    "The proxy started less than two minutes ago.",
                    "Check again once it has been running for a few minutes."));
        }

        eventLoopRules(findings, context, limits);
        memoryRules(findings, context, limits);
        latencyRules(findings, context, limits);
        switchRules(findings, context, limits);
        backendRules(findings, context, limits);
        capacityRules(findings, context, limits);

        if (findings.isEmpty()) {
            findings.add(Finding.info("healthy", "Nothing looks wrong from here",
                    "Event loop, memory and latency are all inside their normal bands.",
                    "If players are still reporting lag, the cause is on a backend server "
                            + "or on their own connection. Run Spark on the backend they are on."));
        }
        return findings;
    }

    // -------------------------------------------------------------- event loop

    private void eventLoopRules(List<Finding> findings, Context context, ScopeConfig.Thresholds limits) {
        Histogram lag = context.eventLoop().snapshot();
        if (lag.count() < MIN_SAMPLES) {
            return;
        }
        long p99 = lag.percentile(0.99D);
        long p50 = lag.percentile(0.50D);

        if (raise("event-loop-critical", p99, limits.eventLoopCriticalMillis())) {
            List<BlockingCallDetector.Suspect> suspects = context.blocking().suspects(3);
            String detail = "Tasks are waiting up to " + p99 + " ms to run on the proxy's I/O threads "
                    + "(median " + p50 + " ms). While that happens, every packet on every backend is queued.";
            String action;
            if (!suspects.isEmpty()) {
                BlockingCallDetector.Suspect worst = suspects.get(0);
                detail += " The stack sampler caught " + worst.pluginId() + " on an I/O thread "
                        + worst.hits() + " time(s), most recently at " + worst.location() + ".";
                action = "Start with " + worst.pluginId() + ". Something in it is running a blocking call "
                        + "on the network thread \u2014 usually a synchronous database or HTTP request "
                        + "that belongs on the scheduler.";
            } else {
                action = "Run /luminscope blocking after the next stall to see which plugin is on the "
                        + "thread. If nothing is caught, the cause is likely garbage collection or the "
                        + "host being oversubscribed.";
            }
            findings.add(Finding.critical("event-loop-critical",
                    "The proxy's I/O threads are being blocked", detail, action));
        } else if (raise("event-loop-warning", p99, limits.eventLoopWarningMillis())) {
            findings.add(Finding.warning("event-loop-warning",
                    "The proxy's I/O threads are running behind",
                    "Scheduling delay reached " + p99 + " ms at the 99th percentile. Players will notice "
                            + "this as intermittent rubber-banding that no backend profiler explains.",
                    "Watch it for a few minutes. If it climbs, run /luminscope blocking to identify "
                            + "which plugin is on the thread."));
        }

        if (!context.eventLoop().direct()) {
            findings.add(Finding.info("probe-indirect",
                    "Event loop timing is approximate on this build",
                    "The probe could not attach to the proxy's I/O executors, so these numbers come "
                            + "from the proxy scheduler instead.",
                    "Everything else is unaffected. This usually means a Velocity version whose "
                            + "internals moved; an update to LuminScope will restore direct timing."));
        }
    }

    // ------------------------------------------------------------------ memory

    private void memoryRules(List<Finding> findings, Context context, ScopeConfig.Thresholds limits) {
        JvmMetrics jvm = context.jvm();

        double heap = jvm.heapFraction();
        if (raise("heap-pressure", (long) (heap * 100), (long) (limits.heapWarningFraction() * 100))) {
            findings.add(Finding.warning("heap-pressure",
                    "The proxy heap is nearly full",
                    String.format(Locale.ROOT, "Heap is at %.0f%% of its maximum (%d MB of %d MB). "
                                    + "A full heap means constant garbage collection, and on a proxy that "
                                    + "shows up as latency for everyone at once.",
                            heap * 100, jvm.heapUsed() / 1_048_576, jvm.heapMax() / 1_048_576),
                    "Raise -Xmx if the host has room. A Velocity proxy rarely needs more than 1 GB, so "
                            + "if it is already above that, something is retaining memory that should not."));
        }

        long longPauses = jvm.longPauses();
        if (raise("gc-pauses", longPauses, limits.longGcPausesPerWindow())) {
            findings.add(Finding.warning("gc-pauses",
                    "Garbage collection is pausing the proxy",
                    longPauses + " collection(s) in the last window averaged over "
                            + JvmMetrics.LONG_PAUSE_MILLIS + " ms, totalling " + jvm.gcMillis() + " ms. "
                            + "Every one of those is a moment where the proxy forwards nothing at all.",
                    "Check that the proxy is using G1 or ZGC rather than the serial collector, and that "
                            + "-Xms equals -Xmx so the heap is not being resized under load."));
        }

        long direct = jvm.directMemoryUsed();
        if (direct > 0 && raise("direct-memory", direct / 1_048_576, limits.directMemoryWarningMb())) {
            findings.add(Finding.warning("direct-memory",
                    "Off-heap buffer usage is high",
                    "Netty is holding " + (direct / 1_048_576) + " MB of direct memory across "
                            + jvm.directBufferCount() + " buffers. Steady growth here is the signature of "
                            + "a buffer leak: the heap looks healthy while the process keeps growing.",
                    "Note the current figure and compare it in an hour. If it only ever rises, a plugin "
                            + "is retaining ByteBufs. If it plateaus, this is normal for your player count."));
        }
    }

    // ----------------------------------------------------------------- latency

    private void latencyRules(List<Finding> findings, Context context, ScopeConfig.Thresholds limits) {
        Histogram client = context.latency().clientRtt();
        Histogram backend = context.latency().backendRttOverall();
        if (client.count() < MIN_SAMPLES) {
            return;
        }

        long clientP95 = client.percentile(0.95D);
        long backendP95 = backend.count() >= MIN_SAMPLES ? backend.percentile(0.95D) : -1;
        boolean clientHigh = raise("client-latency", clientP95, limits.clientRttWarningMillis());
        boolean backendHigh = backendP95 >= 0
                && raise("backend-latency", backendP95, limits.backendRttWarningMillis());

        if (clientHigh && backendHigh) {
            findings.add(Finding.warning("latency-both",
                    "Latency is high on both sides of the proxy",
                    "Players are " + clientP95 + " ms from the proxy and the proxy is " + backendP95
                            + " ms from the backends, both at the 95th percentile. When both halves are "
                            + "slow at once, the proxy machine's own network or CPU is usually the "
                            + "common factor.",
                    "Check the host's network saturation and steal time. A noisy neighbour on shared "
                            + "hosting produces exactly this pattern."));
        } else if (backendHigh) {
            String worst = worstBackend(context);
            findings.add(Finding.warning("latency-backend",
                    "The link between the proxy and the backends is slow",
                    "Proxy-to-backend round trip is " + backendP95 + " ms at the 95th percentile while "
                            + "players are only " + clientP95 + " ms from the proxy"
                            + (worst == null ? "" : ", worst on " + worst) + ". This is the part of the "
                            + "path nothing except the proxy can see.",
                    "If the backends are on other machines, look at the network between them. If they "
                            + "are on the same host, the backend itself is too busy to answer pings, "
                            + "which points at its tick loop rather than the network."));
        } else if (clientHigh) {
            findings.add(Finding.info("latency-client",
                    "Players are far from the proxy",
                    "Player-to-proxy round trip is " + clientP95 + " ms at the 95th percentile while the "
                            + "backends answer in " + (backendP95 < 0 ? "normal time" : backendP95 + " ms") + ".",
                    "The proxy and the backends are both healthy, so this is the players' own routing. "
                            + "It is expected if a large part of your player base is on another continent."));
        }
    }

    // ------------------------------------------------------------------ switch

    private void switchRules(List<Finding> findings, Context context, ScopeConfig.Thresholds limits) {
        Histogram total = context.latency().switchTimeOverall(LatencyTracker.Phase.TOTAL);
        if (total.count() < MIN_SAMPLES) {
            return;
        }
        long p99 = total.percentile(0.99D);
        long p50 = total.percentile(0.50D);

        if (raise("switch-slow", p99, limits.switchWarningMillis())) {
            Histogram connect = context.latency().switchTimeOverall(LatencyTracker.Phase.CONNECT);
            long connectP99 = connect.count() >= MIN_SAMPLES ? connect.percentile(0.99D) : -1;
            String where = connectP99 >= 0 && connectP99 > p99 / 2
                    ? "Most of that is spent getting the backend to accept the connection."
                    : "The backend accepts quickly but takes a long time to finish login and put the "
                      + "player in the world.";
            findings.add(Finding.warning("switch-slow",
                    "Server switches are slow for some players",
                    "Switching takes " + p99 + " ms at the 99th percentile against a median of "
                            + p50 + " ms. " + where + " A player waiting eight seconds on a dark screen "
                            + "assumes the network is broken, even if the median looks fine.",
                    connectP99 >= 0 && connectP99 > p99 / 2
                            ? "Look at backend connection throttling and any join-time plugin work "
                              + "such as permission or profile lookups."
                            : "Look at what runs on the backend at join: world loading, inventory "
                              + "restores and database reads all land here."));
        }
    }

    // ----------------------------------------------------------------- backend

    private void backendRules(List<Finding> findings, Context context, ScopeConfig.Thresholds limits) {
        for (RegisteredServer server : proxy.getAllServers()) {
            String name = server.getServerInfo().getName();
            double failureRate = context.latency().pingFailureRate(name);
            if (failureRate >= 0.5D) {
                findings.add(Finding.critical("backend-down-" + name,
                        "Backend " + name + " is not answering",
                        String.format(Locale.ROOT, "%.0f%% of health checks to %s failed in the last "
                                + "window.", failureRate * 100, name),
                        "The proxy is still routing players there unless self-healing is enabled. "
                                + "Check whether the process is alive and whether it is accepting "
                                + "connections on the port the proxy is configured to use."));
            } else if (failureRate >= 0.15D) {
                findings.add(Finding.warning("backend-flaky-" + name,
                        "Backend " + name + " answers intermittently",
                        String.format(Locale.ROOT, "%.0f%% of health checks to %s failed. Intermittent "
                                + "failures are usually a backend too busy to answer rather than one "
                                + "that is down.", failureRate * 100, name),
                        "Run Spark on " + name + " and look at its tick times. If those are fine, look "
                                + "for packet loss on the link."));
            }

            long switchFailures = context.latency().switchFailures(name);
            if (switchFailures >= limits.switchFailuresPerWindow()) {
                findings.add(Finding.warning("switch-failures-" + name,
                        "Players are being rejected by " + name,
                        switchFailures + " connection attempt(s) to " + name + " were refused in the "
                                + "last window.",
                        "Common causes: the backend hit its player cap, a whitelist or permission "
                                + "check is rejecting them, or online-mode and forwarding secrets do "
                                + "not match between the proxy and the backend."));
            }
        }
    }

    // ---------------------------------------------------------------- capacity

    private void capacityRules(List<Finding> findings, Context context, ScopeConfig.Thresholds limits) {
        JvmMetrics jvm = context.jvm();

        double load = jvm.loadPerCore();
        if (load > 0 && raise("host-load", (long) (load * 100), (long) (limits.loadPerCoreWarning() * 100))) {
            findings.add(Finding.warning("host-load",
                    "The host machine is saturated",
                    String.format(Locale.ROOT, "Load average is %.2f per core across %d core(s). At this "
                            + "level the proxy is competing for CPU with everything else on the box, "
                            + "including your backend servers if they share it.",
                            load, jvm.availableProcessors()),
                    "Find out what else is running on this machine. A proxy needs very little CPU, so "
                            + "if the host is busy, the proxy is a victim rather than a cause."));
        }

        if (raise("thread-growth", jvm.threadCount(), limits.threadCountWarning())) {
            findings.add(Finding.warning("thread-growth",
                    "The proxy is holding an unusual number of threads",
                    jvm.threadCount() + " live threads (peak " + jvm.peakThreadCount() + "). A proxy "
                            + "serving " + context.onlinePlayers() + " players needs a few dozen.",
                    "A plugin is most likely creating threads or executors it never shuts down. "
                            + "Compare the count after a restart and watch how fast it climbs."));
        }
    }

    // ----------------------------------------------------------------- helpers

    private String worstBackend(Context context) {
        String worst = null;
        long worstValue = -1;
        for (String name : context.latency().backends().keySet()) {
            Histogram histogram = context.latency().backendRtt(name);
            if (histogram.count() < MIN_SAMPLES) {
                continue;
            }
            long value = histogram.percentile(0.95D);
            if (value > worstValue) {
                worstValue = value;
                worst = name + " at " + value + " ms";
            }
        }
        return worst;
    }

    /**
     * Threshold test with hysteresis: once raised, a finding needs the value to
     * fall to {@link #CLEAR_RATIO} of the threshold before it clears.
     */
    private boolean raise(String id, long value, long threshold) {
        if (threshold <= 0) {
            return false;
        }
        boolean wasActive = active.contains(id);
        boolean nowActive = wasActive
                ? value > threshold * CLEAR_RATIO
                : value > threshold;
        if (nowActive) {
            active.add(id);
        } else {
            active.remove(id);
        }
        return nowActive;
    }
}
