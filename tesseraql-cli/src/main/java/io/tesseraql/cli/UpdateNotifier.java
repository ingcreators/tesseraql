package io.tesseraql.cli;

import io.tesseraql.core.TesseraqlVersion;
import io.tesseraql.core.version.SemanticVersion;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 38 Tier 1: a passive, opt-out, non-blocking "a newer release is available" notice.
 *
 * <p>The CLI distribution is downloaded by hand (GitHub Releases) and has no package-manager upgrade
 * path yet, so a user on a stale — or broken — build (the 0.3.0 {@code --embedded-db} packaging bug
 * that 0.3.1 fixed) has no in-tool signal that a fix shipped. This closes that blind spot.
 *
 * <p>Design: the notice is printed only from a small per-user cache file, so a run adds <em>zero</em>
 * latency and never touches the network on its hot path. When that cache is missing or older than the
 * check interval, a daemon thread refreshes it for the <em>next</em> run by querying the GitHub
 * Releases API. Every failure (offline, rate-limited, unparsable) is swallowed — the notifier must
 * never change the exit code or break a command. It is silenced by {@code TESSERAQL_NO_UPDATE_NOTIFIER}
 * or any {@code CI} environment, and writes to {@code stderr} so machine-readable {@code stdout} stays
 * clean.
 */
final class UpdateNotifier {

    static final String OPT_OUT_ENV = "TESSERAQL_NO_UPDATE_NOTIFIER";
    static final String HOME_ENV = "TESSERAQL_HOME";

    private static final String REPO = "ingcreators/tesseraql";
    private static final String LATEST_API = "https://api.github.com/repos/" + REPO
            + "/releases/latest";
    private static final String RELEASES_URL = "https://github.com/" + REPO + "/releases/latest";
    private static final Duration CHECK_INTERVAL = Duration.ofHours(24);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern TAG_NAME = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");

    private static final String LAST_CHECK_KEY = "last.check.epoch.second";
    private static final String LATEST_VERSION_KEY = "latest.version";

    private final String currentVersion;
    private final Path cacheFile;
    private final Supplier<Instant> clock;
    private final Supplier<Optional<String>> fetcher;
    private final Duration interval;

    UpdateNotifier(String currentVersion, Path cacheFile, Supplier<Instant> clock,
            Supplier<Optional<String>> fetcher, Duration interval) {
        this.currentVersion = currentVersion;
        this.cacheFile = cacheFile;
        this.clock = clock;
        this.fetcher = fetcher;
        this.interval = interval;
    }

    /**
     * Entry point wired with real defaults; safe to call unconditionally from {@code main}. Prints a
     * cached notice (if any) and kicks off a background refresh. Does nothing when opted out, and
     * never throws.
     */
    static void run(PrintStream err) {
        try {
            if (isOptedOut(System.getenv())) {
                return;
            }
            UpdateNotifier notifier = new UpdateNotifier(
                    TesseraqlVersion.current(),
                    defaultCacheFile(System.getenv()),
                    Instant::now,
                    UpdateNotifier::fetchLatestFromGitHub,
                    CHECK_INTERVAL);
            notifier.notifyFromCache(err);
            notifier.refreshInBackground();
        } catch (RuntimeException ignored) {
            // The version nudge is never worth failing a command over.
        }
    }

    /** Prints the upgrade hint to {@code err} when the cache records a release newer than current. */
    void notifyFromCache(PrintStream err) {
        Properties cache = readCache();
        String latest = cache.getProperty(LATEST_VERSION_KEY);
        if (latest != null && isNewer(latest, currentVersion)) {
            err.println("A newer TesseraQL is available: " + latest + " (current " + currentVersion
                    + "). Download: " + RELEASES_URL);
        }
    }

    /** Refreshes the cache on a daemon thread when it is missing or older than the interval. */
    void refreshInBackground() {
        if (!dueForRefresh()) {
            return;
        }
        Thread thread = new Thread(this::refreshNow, "tesseraql-update-check");
        thread.setDaemon(true);
        thread.start();
    }

    boolean dueForRefresh() {
        Properties cache = readCache();
        String last = cache.getProperty(LAST_CHECK_KEY);
        if (last == null) {
            return true;
        }
        try {
            Instant lastCheck = Instant.ofEpochSecond(Long.parseLong(last.trim()));
            return clock.get().isAfter(lastCheck.plus(interval));
        } catch (NumberFormatException ex) {
            return true;
        }
    }

    /** Queries the latest release and rewrites the cache. Used by the daemon thread (and tests). */
    void refreshNow() {
        Optional<String> latest;
        try {
            latest = fetcher.get();
        } catch (RuntimeException ex) {
            latest = Optional.empty();
        }
        Properties cache = new Properties();
        cache.setProperty(LAST_CHECK_KEY, Long.toString(clock.get().getEpochSecond()));
        latest.ifPresent(version -> cache.setProperty(LATEST_VERSION_KEY, version));
        writeCache(cache);
    }

    private Properties readCache() {
        Properties properties = new Properties();
        if (cacheFile == null || !Files.isRegularFile(cacheFile)) {
            return properties;
        }
        try (var in = Files.newInputStream(cacheFile)) {
            properties.load(in);
        } catch (IOException | IllegalArgumentException ex) {
            return new Properties();
        }
        return properties;
    }

    private void writeCache(Properties properties) {
        if (cacheFile == null) {
            return;
        }
        try {
            Files.createDirectories(cacheFile.getParent());
            Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            try (var out = Files.newOutputStream(tmp)) {
                properties.store(out, "TesseraQL CLI update check");
            }
            Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            // Best-effort cache; a failed write just means we re-check next time.
        }
    }

    static boolean isNewer(String candidate, String current) {
        try {
            return SemanticVersion.parse(candidate).compareTo(SemanticVersion.parse(current)) > 0;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    static boolean isOptedOut(Map<String, String> env) {
        return isTruthy(env.get(OPT_OUT_ENV)) || isTruthy(env.get("CI"));
    }

    private static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return !v.isEmpty() && !v.equals("0") && !v.equals("false") && !v.equals("no");
    }

    static Path defaultCacheFile(Map<String, String> env) {
        String home = env.get(HOME_ENV);
        Path base = (home != null && !home.isBlank())
                ? Path.of(home)
                : Path.of(System.getProperty("user.home", "."), ".tesseraql");
        return base.resolve("update-check.properties");
    }

    static Optional<String> parseTagName(String json) {
        if (json == null) {
            return Optional.empty();
        }
        Matcher matcher = TAG_NAME.matcher(json);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static Optional<String> fetchLatestFromGitHub() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_API))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "tesseraql-cli")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            return parseTagName(response.body());
        } catch (IOException ex) {
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
