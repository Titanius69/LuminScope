package dev.lumin.scope.metrics;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * The latency breakdown that only the proxy can produce.
 *
 * <p>A proxy sits between the player and the backend, which makes it the one
 * place where both halves of the round trip are visible at the same time. That
 * is what turns "the server is lagging" into an answer: if a player is 15 ms
 * from the proxy and the proxy is 180 ms from survival-1, the problem is not the
 * player's connection and it is not the backend's tick rate — it is the link in
 * between, and nothing else on the network could have told you that.
 *
 * <p>Per-player latency is personal data, so only aggregates are kept. Nothing
 * here is stored against a name or an address.
 */
public final class LatencyTracker {

    /** Server switch phases, timed separately because they fail differently. */
    public enum Phase {
        /** Proxy asked the backend for a connection and got one. */
        CONNECT,
        /** Backend finished login and configuration; the player is in. */
        TOTAL
    }

    private record PendingSwitch(long startedAt, String target) {
    }

    private final ProxyServer proxy;

    private final RollingHistogram clientRtt = new RollingHistogram(5);
    private final Map<String, RollingHistogram> backendRtt = new ConcurrentHashMap<>();
    private final Map<String, RollingHistogram> switchConnect = new ConcurrentHashMap<>();
    private final Map<String, RollingHistogram> switchTotal = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> pingFailures = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> pingAttempts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> switchFailures = new ConcurrentHashMap<>();
    private final Map<UUID, PendingSwitch> pending = new ConcurrentHashMap<>();

    /** Notified with (server, healthy) after every individual health check. */
    private volatile BiConsumer<String, Boolean> pingListener = (server, healthy) -> {
    };

    public LatencyTracker(ProxyServer proxy) {
        this.proxy = proxy;
    }

    public void onPingResult(BiConsumer<String, Boolean> listener) {
        this.pingListener = listener;
    }

    // ------------------------------------------------------------ client side

    /** Folds every online player's keepalive round trip into the aggregate. */
    public void sampleClientLatency() {
        for (Player player : proxy.getAllPlayers()) {
            long ping = player.getPing();
            if (ping >= 0) {
                clientRtt.record(ping);
            }
        }
    }

    public Histogram clientRtt() {
        return clientRtt.snapshot();
    }

    // ----------------------------------------------------------- backend side

    /** Pings every backend and records the round trip. Fully asynchronous. */
    public void sampleBackendLatency(int timeoutSeconds) {
        for (RegisteredServer server : proxy.getAllServers()) {
            String name = server.getServerInfo().getName();
            long startedAt = System.nanoTime();
            pingAttempts.computeIfAbsent(name, key -> new AtomicLong()).incrementAndGet();
            server.ping()
                    .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .whenComplete((response, error) -> {
                        if (error != null || response == null) {
                            pingFailures.computeIfAbsent(name, key -> new AtomicLong()).incrementAndGet();
                            pingListener.accept(name, Boolean.FALSE);
                            return;
                        }
                        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                        backendRtt.computeIfAbsent(name, key -> new RollingHistogram(5)).record(millis);
                        pingListener.accept(name, Boolean.TRUE);
                    });
        }
    }

    public Histogram backendRtt(String server) {
        RollingHistogram histogram = backendRtt.get(server);
        return histogram == null ? new Histogram() : histogram.snapshot();
    }

    /** Merged view across every backend. */
    public Histogram backendRttOverall() {
        Histogram merged = new Histogram();
        backendRtt.values().forEach(histogram -> merged.mergeFrom(histogram.snapshot()));
        return merged;
    }

    public Map<String, RollingHistogram> backends() {
        return backendRtt;
    }

    public double pingFailureRate(String server) {
        long attempts = counter(pingAttempts, server);
        return attempts == 0 ? 0 : (double) counter(pingFailures, server) / attempts;
    }

    // ------------------------------------------------------------ switch side

    public void switchStarted(UUID player, String target) {
        pending.put(player, new PendingSwitch(System.nanoTime(), target));
    }

    /** The backend accepted the connection. Records the connect phase. */
    public void switchConnected(UUID player) {
        PendingSwitch start = pending.get(player);
        if (start == null) {
            return;
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start.startedAt());
        switchConnect.computeIfAbsent(start.target(), key -> new RollingHistogram(5)).record(millis);
    }

    /** The player is fully in. Records the total and forgets the attempt. */
    public void switchFinished(UUID player) {
        PendingSwitch start = pending.remove(player);
        if (start == null) {
            return;
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start.startedAt());
        switchTotal.computeIfAbsent(start.target(), key -> new RollingHistogram(5)).record(millis);
    }

    public void switchFailed(UUID player, String target) {
        pending.remove(player);
        switchFailures.computeIfAbsent(target, key -> new AtomicLong()).incrementAndGet();
    }

    public void forget(UUID player) {
        pending.remove(player);
    }

    public Histogram switchTime(String server, Phase phase) {
        Map<String, RollingHistogram> source = phase == Phase.CONNECT ? switchConnect : switchTotal;
        RollingHistogram histogram = source.get(server);
        return histogram == null ? new Histogram() : histogram.snapshot();
    }

    public Histogram switchTimeOverall(Phase phase) {
        Map<String, RollingHistogram> source = phase == Phase.CONNECT ? switchConnect : switchTotal;
        Histogram merged = new Histogram();
        source.values().forEach(histogram -> merged.mergeFrom(histogram.snapshot()));
        return merged;
    }

    public long switchFailures(String server) {
        return counter(switchFailures, server);
    }

    // --------------------------------------------------------------- lifecycle

    /** Advances every rolling window. Called once per window period. */
    public void rotate() {
        clientRtt.rotate();
        backendRtt.values().forEach(RollingHistogram::rotate);
        switchConnect.values().forEach(RollingHistogram::rotate);
        switchTotal.values().forEach(RollingHistogram::rotate);

        // Attempt counters are ratios over the window, so they reset with it.
        pingAttempts.values().forEach(counter -> counter.set(0));
        pingFailures.values().forEach(counter -> counter.set(0));
        switchFailures.values().forEach(counter -> counter.set(0));

        // A switch that never completed is a leaked entry. Anything older than a
        // minute is gone for good.
        long cutoff = System.nanoTime() - TimeUnit.MINUTES.toNanos(1);
        pending.values().removeIf(value -> value.startedAt() < cutoff);
    }

    private static long counter(Map<String, AtomicLong> source, String key) {
        AtomicLong value = source.get(key);
        return value == null ? 0 : value.get();
    }
}
