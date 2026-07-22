package dev.lumin.scope;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.lumin.scope.command.ScopeCommand;
import dev.lumin.scope.config.ScopeConfig;
import dev.lumin.scope.diagnosis.Finding;
import dev.lumin.scope.diagnosis.RuleEngine;
import dev.lumin.scope.export.PrometheusExporter;
import dev.lumin.scope.heal.SelfHealing;
import dev.lumin.scope.metrics.EventLoopProbe;
import dev.lumin.scope.metrics.JvmMetrics;
import dev.lumin.scope.metrics.LatencyTracker;
import dev.lumin.scope.notify.ScopeAlerter;
import dev.lumin.scope.update.SpigotUpdateChecker;
import dev.lumin.scope.watchdog.BlockingCallDetector;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * LuminScope: diagnostics for the layer nobody instruments.
 *
 * <p>"The server is lagging" can mean at least seven different things: a slow
 * backend tick loop, long garbage collection pauses on the proxy, a slow link
 * between proxy and backend, a bad route on the player's side, a plugin blocking
 * the network threads, a slow database call, or a weak client machine. Spark
 * profiles backends. Plan measures players. The proxy — the one component every
 * packet passes through, and the only place where both halves of the round trip
 * are visible at once — has had nothing.
 *
 * <p>This plugin measures that layer and, more importantly, draws conclusions
 * from it. Raw metrics are not the deliverable; a sentence naming the likely
 * cause is.
 */
@Plugin(
        id = "luminscope",
        name = "LuminScope",
        version = LuminScopePlugin.VERSION,
        description = "Proxy-side diagnostics: latency breakdown, event loop stalls, and root-cause hints.",
        url = "https://github.com/lumin-mc/LuminScope",
        authors = {"Lumin"}
)
public final class LuminScopePlugin {

    public static final String VERSION = "1.0.0";

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private final List<ScheduledTask> tasks = new ArrayList<>();

    private volatile ScopeConfig config;
    private volatile List<Finding> latestFindings = List.of();

    private JvmMetrics jvm;
    private EventLoopProbe eventLoop;
    private LatencyTracker latency;
    private BlockingCallDetector blocking;
    private RuleEngine rules;
    private ScopeAlerter alerter;
    private PrometheusExporter prometheus;
    private SelfHealing selfHealing;
    private SpigotUpdateChecker updateChecker;

    @Inject
    public LuminScopePlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            config = ScopeConfig.load(dataDirectory);
        } catch (Exception ex) {
            logger.error("Could not read config.conf. LuminScope will not start; "
                    + "fix the file and restart the proxy.", ex);
            return;
        }

        jvm = new JvmMetrics();
        eventLoop = new EventLoopProbe(proxy, logger);
        eventLoop.bindPlugin(this);
        eventLoop.discover();

        latency = new LatencyTracker(proxy);
        blocking = new BlockingCallDetector(proxy);
        blocking.indexPlugins();

        rules = new RuleEngine(proxy);
        alerter = new ScopeAlerter(config, logger);
        selfHealing = new SelfHealing(proxy, logger, config, message -> alerter.notice(message));
        latency.onPingResult((server, healthy) -> selfHealing.report(server, healthy));

        prometheus = new PrometheusExporter(proxy, logger, jvm, eventLoop, latency);
        updateChecker = new SpigotUpdateChecker(logger, "LuminScope", VERSION,
                config.updateChecker().resourceId());

        CommandManager commands = proxy.getCommandManager();
        CommandMeta meta = commands.metaBuilder("luminscope")
                .aliases("lscope", "scope")
                .plugin(this)
                .build();
        commands.register(meta, new ScopeCommand(this).build());

        startServices();

        logger.info("LuminScope {} is watching the proxy. Event loop timing: {}.",
                VERSION, eventLoop.direct() ? "direct" : "approximate");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        stopServices();
    }

    // ------------------------------------------------------------------ events

    /**
     * Times the switch and, when self-healing is on, keeps players away from a
     * backend that has stopped answering.
     *
     * <p>Runs late so any plugin that actually routes players has already had its
     * say; this only overrides the result when the chosen target is drained.
     */
    @Subscribe(order = PostOrder.LATE)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Optional<RegisteredServer> target = event.getResult().getServer();
        if (target.isEmpty()) {
            return;
        }
        String name = target.get().getServerInfo().getName();

        Optional<RegisteredServer> replacement = selfHealing.reroute(name);
        if (replacement.isPresent()) {
            logger.info("Routing {} to {} instead of {}, which is out of rotation.",
                    event.getPlayer().getUsername(),
                    replacement.get().getServerInfo().getName(), name);
            event.setResult(ServerPreConnectEvent.ServerResult.allowed(replacement.get()));
            latency.switchStarted(event.getPlayer().getUniqueId(),
                    replacement.get().getServerInfo().getName());
            return;
        }
        latency.switchStarted(event.getPlayer().getUniqueId(), name);
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        latency.switchConnected(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        latency.switchFinished(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        if (event.kickedDuringServerConnect()) {
            latency.switchFailed(event.getPlayer().getUniqueId(),
                    event.getServer().getServerInfo().getName());
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        latency.forget(event.getPlayer().getUniqueId());
    }

    // --------------------------------------------------------------- lifecycle

    private void startServices() {
        ScopeConfig current = config;

        if (current.prometheus().enabled()) {
            prometheus.start(current.prometheus());
        }

        // The event loop probe runs far more often than anything else; it is a
        // no-op task submission, so the cost is negligible and the resolution
        // matters.
        tasks.add(proxy.getScheduler().buildTask(this, this::probeEventLoop)
                .delay(5, TimeUnit.SECONDS)
                .repeat(current.sampling().eventLoopProbeMillis(), TimeUnit.MILLISECONDS)
                .schedule());

        tasks.add(proxy.getScheduler().buildTask(this, this::sample)
                .delay(10, TimeUnit.SECONDS)
                .repeat(current.sampling().intervalSeconds(), TimeUnit.SECONDS)
                .schedule());

        tasks.add(proxy.getScheduler().buildTask(this, this::analyse)
                .delay(30, TimeUnit.SECONDS)
                .repeat(Math.max(15, current.sampling().intervalSeconds() * 3L), TimeUnit.SECONDS)
                .schedule());

        // Rolling windows advance once per window slice, giving a view of the
        // last window-minutes rather than of all time.
        long slice = Math.max(10L, current.sampling().windowMinutes() * 60L / 5L);
        tasks.add(proxy.getScheduler().buildTask(this, this::rotate)
                .delay(slice, TimeUnit.SECONDS)
                .repeat(slice, TimeUnit.SECONDS)
                .schedule());

        if (current.updateChecker().enabled() && current.updateChecker().resourceId() > 0) {
            tasks.add(proxy.getScheduler().buildTask(this, () -> updateChecker.check())
                    .delay(30, TimeUnit.SECONDS)
                    .repeat(current.updateChecker().intervalHours(), TimeUnit.HOURS)
                    .schedule());
        }
    }

    private void stopServices() {
        tasks.forEach(ScheduledTask::cancel);
        tasks.clear();
        if (prometheus != null) {
            prometheus.stop();
        }
    }

    public boolean reload() {
        ScopeConfig loaded;
        try {
            loaded = ScopeConfig.load(dataDirectory);
        } catch (Exception ex) {
            logger.error("Reload failed, keeping the previous configuration", ex);
            return false;
        }
        stopServices();
        config = loaded;
        alerter.applyConfig(loaded);
        selfHealing.applyConfig(loaded);
        blocking.indexPlugins();
        eventLoop.discover();
        updateChecker = new SpigotUpdateChecker(logger, "LuminScope", VERSION,
                loaded.updateChecker().resourceId());
        startServices();
        return true;
    }

    // ----------------------------------------------------------------- sampling

    private void probeEventLoop() {
        eventLoop.probe();

        ScopeConfig current = config;
        if (!current.blocking().enabled()) {
            return;
        }
        // Sampling stacks is only cheap because it happens exclusively while the
        // proxy is already stalled. Never sample on the happy path.
        if (eventLoop.lastLagMillis() >= current.blocking().triggerMillis()) {
            blocking.sample();
        }
    }

    private void sample() {
        ScopeConfig current = config;
        jvm.sample();
        if (current.sampling().sampleClientLatency()) {
            latency.sampleClientLatency();
        }
        latency.sampleBackendLatency(current.sampling().backendTimeoutSeconds());
    }

    private void analyse() {
        List<Finding> findings = rules.evaluate(new RuleEngine.Context(
                config, eventLoop, jvm, latency, blocking, proxy.getPlayerCount()));
        latestFindings = List.copyOf(findings);
        alerter.publish(findings);
    }

    private void rotate() {
        eventLoop.rotate();
        latency.rotate();
        jvm.rotate();
    }

    /** Clears every accumulated counter so measurement restarts from now. */
    public void resetCounters() {
        blocking.reset();
        jvm.rotate();
        for (int i = 0; i < 5; i++) {
            eventLoop.rotate();
            latency.rotate();
        }
        latestFindings = List.of();
    }

    // --------------------------------------------------------------- accessors

    public ProxyServer proxy() {
        return proxy;
    }

    public ScopeConfig config() {
        return config;
    }

    public JvmMetrics jvm() {
        return jvm;
    }

    public EventLoopProbe eventLoop() {
        return eventLoop;
    }

    public LatencyTracker latency() {
        return latency;
    }

    public BlockingCallDetector blocking() {
        return blocking;
    }

    public SelfHealing selfHealing() {
        return selfHealing;
    }

    public SpigotUpdateChecker updateChecker() {
        return updateChecker;
    }

    public List<Finding> latestFindings() {
        return latestFindings;
    }
}
