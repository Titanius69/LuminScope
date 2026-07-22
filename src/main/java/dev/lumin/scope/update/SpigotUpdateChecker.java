package dev.lumin.scope.update;

import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks SpigotMC for a newer release of this resource.
 *
 * <p>The legacy update endpoint returns the latest version as bare text, which
 * is all that is needed. The check is asynchronous, never blocks startup, and
 * failures are logged at debug level only: a proxy that cannot reach spigotmc.org
 * is not a problem this plugin should complain about on every boot.
 */
public final class SpigotUpdateChecker {

    private static final String ENDPOINT = "https://api.spigotmc.org/legacy/update.php?resource=";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Logger logger;
    private final String pluginName;
    private final String currentVersion;
    private final int resourceId;
    private final AtomicReference<String> latest = new AtomicReference<>();

    public SpigotUpdateChecker(Logger logger, String pluginName, String currentVersion, int resourceId) {
        this.logger = logger;
        this.pluginName = pluginName;
        this.currentVersion = currentVersion;
        this.resourceId = resourceId;
    }

    /** The newest version seen, or {@code null} if no check has succeeded yet. */
    public String latestVersion() {
        return latest.get();
    }

    public boolean updateAvailable() {
        String remote = latest.get();
        return remote != null && isNewer(remote, currentVersion);
    }

    /** Runs one check. Safe to call from any thread. */
    public CompletableFuture<Void> check() {
        if (resourceId <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT + resourceId))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", pluginName + "/" + currentVersion)
                .GET()
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        logger.debug("Update check returned HTTP {}", response.statusCode());
                        return;
                    }
                    String remote = response.body().trim();
                    if (remote.isEmpty() || remote.length() > 32) {
                        return;
                    }
                    latest.set(remote);
                    if (isNewer(remote, currentVersion)) {
                        logger.info("{} {} is available (running {}). Download: "
                                        + "https://www.spigotmc.org/resources/{}/",
                                pluginName, remote, currentVersion, resourceId);
                    }
                })
                .exceptionally(error -> {
                    logger.debug("Update check failed: {}", error.getMessage());
                    return null;
                });
    }

    /**
     * Compares dotted version strings numerically, ignoring any suffix such as
     * {@code -SNAPSHOT}. Returns true when {@code remote} is strictly newer.
     */
    static boolean isNewer(String remote, String local) {
        int[] a = parse(remote);
        int[] b = parse(local);
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int left = i < a.length ? a[i] : 0;
            int right = i < b.length ? b[i] : 0;
            if (left != right) {
                return left > right;
            }
        }
        return false;
    }

    private static int[] parse(String version) {
        String cleaned = version.split("[-+]", 2)[0].trim();
        String[] parts = cleaned.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ex) {
                out[i] = 0;
            }
        }
        return out;
    }
}
