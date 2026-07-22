package dev.lumin.scope.heal;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.lumin.scope.config.ScopeConfig;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Takes an unresponsive backend out of the routing rotation, and puts it back
 * carefully.
 *
 * <p>Off by default, and deliberately timid when on. Automation that fires at
 * the wrong moment is worse than no automation: a proxy that drains a healthy
 * server because of a thirty-second network blip has turned a non-event into an
 * outage. So every action here is conservative, reversible, announced, and
 * refuses to act when acting could make things worse — it will never drain the
 * last remaining server, and never touches anything on the protected list.
 *
 * <p>Recovery is gradual. A server that just came back has cold caches and an
 * empty connection pool; sending it the entire queued population at once is how
 * a recovering server dies a second time.
 */
public final class SelfHealing {

    /** Where a backend sits in the drain and recovery cycle. */
    public enum Status {
        /** Taking traffic normally. */
        HEALTHY,
        /** Removed from routing after repeated failures. */
        DRAINED,
        /** Answering again; taking a growing share of new connections. */
        RAMPING
    }

    private static final class Record {
        int consecutiveFailures;
        int consecutiveSuccesses;
        Status status = Status.HEALTHY;
        long rampStartedAt;
    }

    private final ProxyServer proxy;
    private final Logger logger;
    private final Map<String, Record> records = new ConcurrentHashMap<>();
    private final Consumer<String> announcer;

    private volatile ScopeConfig config;

    /**
     * Runtime switch, so an operator can turn healing on mid-incident without a
     * reload and turn it off again the moment it does something surprising.
     */
    private volatile boolean enabled;

    public SelfHealing(ProxyServer proxy, Logger logger, ScopeConfig config, Consumer<String> announcer) {
        this.proxy = proxy;
        this.logger = logger;
        this.config = config;
        this.enabled = config.selfHealing().enabled();
        this.announcer = announcer;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            records.values().forEach(record -> {
                record.status = Status.HEALTHY;
                record.consecutiveFailures = 0;
            });
        }
    }

    public void applyConfig(ScopeConfig config) {
        this.config = config;
        this.enabled = config.selfHealing().enabled();
        if (!enabled) {
            // Turning the feature off must not leave servers drained.
            records.values().forEach(record -> {
                record.status = Status.HEALTHY;
                record.consecutiveFailures = 0;
            });
        }
    }

    public Status status(String server) {
        Record record = records.get(server);
        return record == null ? Status.HEALTHY : record.status;
    }

    public Map<String, Status> statuses() {
        Map<String, Status> out = new ConcurrentHashMap<>();
        records.forEach((name, record) -> out.put(name, record.status));
        return out;
    }

    /** Feeds one health check result in. Called after every ping round. */
    public void report(String server, boolean healthy) {
        ScopeConfig.SelfHealing settings = config.selfHealing();
        Record record = records.computeIfAbsent(server, key -> new Record());

        synchronized (record) {
            if (healthy) {
                record.consecutiveFailures = 0;
                record.consecutiveSuccesses++;
                if (record.status == Status.DRAINED
                        && record.consecutiveSuccesses >= settings.recoveryChecks()) {
                    record.status = Status.RAMPING;
                    record.rampStartedAt = System.currentTimeMillis();
                    announce(server + " is answering again. Easing traffic back over "
                            + settings.rampSeconds() + " seconds.");
                } else if (record.status == Status.RAMPING && rampFraction(record) >= 1.0D) {
                    record.status = Status.HEALTHY;
                    announce(server + " is back in normal rotation.");
                }
                return;
            }

            record.consecutiveSuccesses = 0;
            record.consecutiveFailures++;
            if (record.status == Status.DRAINED
                    || !enabled
                    || record.consecutiveFailures < settings.unhealthyChecks()) {
                return;
            }
            if (settings.neverRemove().contains(server)) {
                return;
            }
            if (!hasFallbackOtherThan(server)) {
                // Draining the only reachable server would turn a partial outage
                // into a total one.
                logger.warn("{} is failing health checks, but it is the only server left in rotation. "
                        + "Leaving it in.", server);
                return;
            }
            record.status = Status.DRAINED;
            announce(server + " failed " + record.consecutiveFailures
                    + " health checks in a row and has been taken out of the routing rotation. "
                    + "New players will be sent to a fallback server instead.");
        }
    }

    /**
     * Decides whether a connection to this server should go ahead.
     *
     * @return empty to allow it, or the server to use instead
     */
    public Optional<RegisteredServer> reroute(String target) {
        if (!enabled) {
            return Optional.empty();
        }
        Record record = records.get(target);
        if (record == null) {
            return Optional.empty();
        }
        synchronized (record) {
            if (record.status == Status.HEALTHY) {
                return Optional.empty();
            }
            if (record.status == Status.RAMPING) {
                double fraction = rampFraction(record);
                if (fraction >= 1.0D) {
                    record.status = Status.HEALTHY;
                    return Optional.empty();
                }
                if (ThreadLocalRandom.current().nextDouble() < fraction) {
                    return Optional.empty();
                }
            }
        }
        return fallbackFor(target);
    }

    private double rampFraction(Record record) {
        int rampSeconds = config.selfHealing().rampSeconds();
        if (rampSeconds <= 0) {
            return 1.0D;
        }
        long elapsed = System.currentTimeMillis() - record.rampStartedAt;
        return Math.min(1.0D, (double) elapsed / (rampSeconds * 1000L));
    }

    private Optional<RegisteredServer> fallbackFor(String excluded) {
        List<String> configured = config.selfHealing().fallbackServers();
        for (String name : configured) {
            if (name.equalsIgnoreCase(excluded)) {
                continue;
            }
            Optional<RegisteredServer> server = proxy.getServer(name);
            if (server.isPresent() && status(name) == Status.HEALTHY) {
                return server;
            }
        }
        // Nothing configured or nothing healthy on the list: fall back to any
        // healthy server rather than dropping the player entirely.
        for (RegisteredServer server : proxy.getAllServers()) {
            String name = server.getServerInfo().getName();
            if (!name.equalsIgnoreCase(excluded) && status(name) == Status.HEALTHY) {
                return Optional.of(server);
            }
        }
        return Optional.empty();
    }

    private boolean hasFallbackOtherThan(String excluded) {
        for (RegisteredServer server : proxy.getAllServers()) {
            String name = server.getServerInfo().getName();
            if (!name.equalsIgnoreCase(excluded) && status(name) == Status.HEALTHY) {
                return true;
            }
        }
        return false;
    }

    private void announce(String message) {
        logger.warn("[self-healing] {}", message);
        announcer.accept(message);
    }
}
