package dev.lumin.scope.config;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Typed view over {@code config.conf}.
 *
 * <p>Thresholds are deliberately conservative. An observability tool that cries
 * wolf gets muted, and a muted tool is worse than no tool because it creates the
 * impression that someone is watching.
 */
public final class ScopeConfig {

    public record Sampling(int intervalSeconds,
                           int windowMinutes,
                           int backendTimeoutSeconds,
                           int eventLoopProbeMillis,
                           boolean sampleClientLatency) {
    }

    public record Thresholds(long eventLoopWarningMillis,
                             long eventLoopCriticalMillis,
                             double heapWarningFraction,
                             long longGcPausesPerWindow,
                             long directMemoryWarningMb,
                             long clientRttWarningMillis,
                             long backendRttWarningMillis,
                             long switchWarningMillis,
                             long switchFailuresPerWindow,
                             double loadPerCoreWarning,
                             long threadCountWarning) {
    }

    public record Blocking(boolean enabled, long triggerMillis, int maxSuspects) {
    }

    public record Alerts(boolean enabled,
                         String webhookUrl,
                         String minSeverity,
                         int cooldownMinutes,
                         String mention) {
    }

    public record Prometheus(boolean enabled, String bindAddress, int port, String path, String bearerToken) {
    }

    public record SelfHealing(boolean enabled,
                              int unhealthyChecks,
                              int recoveryChecks,
                              int rampSeconds,
                              List<String> fallbackServers,
                              List<String> neverRemove) {
    }

    public record UpdateChecker(boolean enabled, int resourceId, int intervalHours) {
    }

    private final Sampling sampling;
    private final Thresholds thresholds;
    private final Blocking blocking;
    private final Alerts alerts;
    private final Prometheus prometheus;
    private final SelfHealing selfHealing;
    private final UpdateChecker updateChecker;

    private ScopeConfig(Sampling sampling,
                        Thresholds thresholds,
                        Blocking blocking,
                        Alerts alerts,
                        Prometheus prometheus,
                        SelfHealing selfHealing,
                        UpdateChecker updateChecker) {
        this.sampling = sampling;
        this.thresholds = thresholds;
        this.blocking = blocking;
        this.alerts = alerts;
        this.prometheus = prometheus;
        this.selfHealing = selfHealing;
        this.updateChecker = updateChecker;
    }

    public Sampling sampling() {
        return sampling;
    }

    public Thresholds thresholds() {
        return thresholds;
    }

    public Blocking blocking() {
        return blocking;
    }

    public Alerts alerts() {
        return alerts;
    }

    public Prometheus prometheus() {
        return prometheus;
    }

    public SelfHealing selfHealing() {
        return selfHealing;
    }

    public UpdateChecker updateChecker() {
        return updateChecker;
    }

    public static ScopeConfig load(Path dataDirectory) throws IOException, ConfigurateException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve("config.conf");
        if (Files.notExists(file)) {
            try (InputStream in = ScopeConfig.class.getResourceAsStream("/config.conf")) {
                if (in == null) {
                    throw new IOException("Bundled config.conf is missing from the jar");
                }
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        CommentedConfigurationNode root = HoconConfigurationLoader.builder().path(file).build().load();

        Sampling sampling = new Sampling(
                clamp(root.node("sampling", "interval-seconds").getInt(10), 1, 300),
                clamp(root.node("sampling", "window-minutes").getInt(5), 1, 60),
                clamp(root.node("sampling", "backend-timeout-seconds").getInt(5), 1, 60),
                clamp(root.node("sampling", "event-loop-probe-millis").getInt(250), 50, 5000),
                root.node("sampling", "sample-client-latency").getBoolean(true)
        );

        Thresholds thresholds = new Thresholds(
                root.node("thresholds", "event-loop-warning-millis").getLong(50L),
                root.node("thresholds", "event-loop-critical-millis").getLong(250L),
                root.node("thresholds", "heap-warning-fraction").getDouble(0.90D),
                root.node("thresholds", "long-gc-pauses-per-window").getLong(3L),
                root.node("thresholds", "direct-memory-warning-mb").getLong(512L),
                root.node("thresholds", "client-rtt-warning-millis").getLong(250L),
                root.node("thresholds", "backend-rtt-warning-millis").getLong(100L),
                root.node("thresholds", "switch-warning-millis").getLong(5000L),
                root.node("thresholds", "switch-failures-per-window").getLong(10L),
                root.node("thresholds", "load-per-core-warning").getDouble(0.90D),
                root.node("thresholds", "thread-count-warning").getLong(200L)
        );

        Blocking blocking = new Blocking(
                root.node("blocking-detection", "enabled").getBoolean(true),
                root.node("blocking-detection", "trigger-millis").getLong(100L),
                clamp(root.node("blocking-detection", "max-suspects").getInt(5), 1, 20)
        );

        Alerts alerts = new Alerts(
                root.node("alerts", "enabled").getBoolean(false),
                root.node("alerts", "webhook-url").getString(""),
                root.node("alerts", "min-severity").getString("warning"),
                clamp(root.node("alerts", "cooldown-minutes").getInt(30), 1, 1440),
                root.node("alerts", "mention").getString("")
        );

        Prometheus prometheus = new Prometheus(
                root.node("prometheus", "enabled").getBoolean(false),
                root.node("prometheus", "bind-address").getString("127.0.0.1"),
                clamp(root.node("prometheus", "port").getInt(9225), 1, 65535),
                root.node("prometheus", "path").getString("/metrics"),
                root.node("prometheus", "bearer-token").getString("")
        );

        SelfHealing selfHealing = new SelfHealing(
                root.node("self-healing", "enabled").getBoolean(false),
                clamp(root.node("self-healing", "unhealthy-checks").getInt(4), 2, 50),
                clamp(root.node("self-healing", "recovery-checks").getInt(6), 2, 50),
                clamp(root.node("self-healing", "ramp-seconds").getInt(120), 0, 3600),
                stringList(root.node("self-healing", "fallback-servers")),
                stringList(root.node("self-healing", "never-remove"))
        );

        UpdateChecker updateChecker = new UpdateChecker(
                root.node("update-checker", "enabled").getBoolean(true),
                root.node("update-checker", "resource-id").getInt(0),
                clamp(root.node("update-checker", "interval-hours").getInt(6), 1, 168)
        );

        return new ScopeConfig(sampling, thresholds, blocking, alerts, prometheus, selfHealing, updateChecker);
    }

    private static List<String> stringList(CommentedConfigurationNode node) {
        try {
            List<String> values = node.getList(String.class);
            return values == null ? List.of() : List.copyOf(values);
        } catch (ConfigurateException ex) {
            return List.of();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
