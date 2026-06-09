package io.tesseraql.operations.app;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.version.SemanticVersion;
import io.tesseraql.core.version.VersionRange;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Upgrades an installed app with a preflight, snapshot, and rollback lifecycle (design ch. 31),
 * gated by the version compatibility matrix (ch. 30).
 *
 * <p>A direct upgrade activates the new version immediately (snapshotting the previous one for
 * rollback). A canary upgrade stages the new version on disk without activating it, so it can run
 * alongside the current version (via multi-app hosting) and then be {@link #promote promoted} or
 * {@link #rollback rolled back}.
 */
public final class AppUpgrader {

    private static final TqlErrorCode INCOMPATIBLE = new TqlErrorCode(TqlDomain.UPGRADE, 4090);
    private static final TqlErrorCode NO_TARGET = new TqlErrorCode(TqlDomain.UPGRADE, 4091);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_CANARY_WEIGHT = 10;

    private final AppInstaller installer = new AppInstaller();

    /** Validates a candidate package against the current install and the framework version. */
    public UpgradeReport preflight(Path tqlapp, Path installRoot, SemanticVersion frameworkVersion) {
        AppInstaller.PackageInfo info = installer.peek(tqlapp);
        Optional<InstalledApp> current = new AppCatalog(installRoot).find(info.id());
        List<String> messages = new ArrayList<>();

        SemanticVersion to;
        try {
            to = SemanticVersion.parse(info.version());
        } catch (IllegalArgumentException ex) {
            messages.add("Package version is not a valid version: " + info.version());
            return new UpgradeReport(false, info.id(),
                    current.map(InstalledApp::version).orElse(null), info.version(), messages);
        }

        if (current.isPresent()) {
            SemanticVersion from = SemanticVersion.parse(current.get().version());
            if (to.compareTo(from) <= 0) {
                messages.add("Candidate version " + to + " is not newer than installed " + from);
            }
        }

        VersionRange required = VersionRange.parse(info.requiresFramework());
        if (!required.includes(frameworkVersion)) {
            messages.add("Package requires framework " + required + " but runtime is " + frameworkVersion);
        }

        return new UpgradeReport(messages.isEmpty(), info.id(),
                current.map(InstalledApp::version).orElse(null), info.version(), messages);
    }

    /** Activates the new version immediately, snapshotting the previous one for rollback. */
    public UpgradeResult upgrade(Path tqlapp, Path installRoot, SemanticVersion frameworkVersion) {
        return upgrade(tqlapp, installRoot, frameworkVersion, false);
    }

    /**
     * Upgrades the app. When {@code canary} is true the new version is staged but not activated;
     * call {@link #promote} to activate it or {@link #rollback} to discard it.
     */
    public UpgradeResult upgrade(Path tqlapp, Path installRoot, SemanticVersion frameworkVersion,
            boolean canary) {
        UpgradeReport report = preflight(tqlapp, installRoot, frameworkVersion);
        if (!report.compatible()) {
            throw new TqlException(INCOMPATIBLE,
                    "Upgrade preflight failed: " + String.join("; ", report.messages()));
        }
        AppCatalog catalog = new AppCatalog(installRoot);
        InstalledApp previous = catalog.find(report.appId()).orElse(null);
        List<String> entitled = previous == null ? List.of() : previous.entitledTenants();

        InstalledApp placed = installer.place(tqlapp, installRoot, null, entitled);
        if (canary) {
            writeState(installRoot, report.appId(),
                    new UpgradeState(previous, placed, DEFAULT_CANARY_WEIGHT));
        } else {
            catalog.register(placed);
            writeState(installRoot, report.appId(), new UpgradeState(previous, null, 0));
        }
        return new UpgradeResult(report.appId(), report.fromVersion(), report.toVersion(), canary);
    }

    /** Adjusts the percentage of traffic the staged canary candidate should receive (0-100). */
    public void setCanaryWeight(String appId, Path installRoot, int weightPercent) {
        UpgradeState state = readState(installRoot, appId);
        if (state == null || state.candidate() == null) {
            throw new TqlException(NO_TARGET, "No staged candidate for app: " + appId);
        }
        int weight = Math.max(0, Math.min(100, weightPercent));
        writeState(installRoot, appId, new UpgradeState(state.previous(), state.candidate(), weight));
    }

    /** The staged canary candidate and its traffic weight, if a canary is in progress. */
    public Optional<CanaryStatus> canary(String appId, Path installRoot) {
        UpgradeState state = readState(installRoot, appId);
        if (state == null || state.candidate() == null) {
            return Optional.empty();
        }
        return Optional.of(new CanaryStatus(state.candidate(), state.canaryWeight()));
    }

    /** Activates a previously staged canary version. */
    public InstalledApp promote(String appId, Path installRoot) {
        UpgradeState state = readState(installRoot, appId);
        if (state == null || state.candidate() == null) {
            throw new TqlException(NO_TARGET, "No staged candidate to promote for app: " + appId);
        }
        new AppCatalog(installRoot).register(state.candidate());
        writeState(installRoot, appId, new UpgradeState(state.previous(), null, 0));
        return state.candidate();
    }

    /**
     * Reverts the last upgrade: discards a pending canary, or restores the snapshotted previous
     * version as active. The previous version's files must still be present.
     */
    public InstalledApp rollback(String appId, Path installRoot) {
        UpgradeState state = readState(installRoot, appId);
        if (state == null) {
            throw new TqlException(NO_TARGET, "Nothing to roll back for app: " + appId);
        }
        if (state.candidate() != null) {
            // Canary not promoted: the catalog still points to the previous version; just discard.
            writeState(installRoot, appId, new UpgradeState(state.previous(), null, 0));
            return state.previous();
        }
        InstalledApp previous = state.previous();
        if (previous == null) {
            throw new TqlException(NO_TARGET, "No previous version to roll back to for app: " + appId);
        }
        if (!Files.isDirectory(installRoot.resolve(previous.path()))) {
            throw new TqlException(NO_TARGET,
                    "Previous version files are missing for app: " + appId);
        }
        new AppCatalog(installRoot).register(previous);
        writeState(installRoot, appId, new UpgradeState(null, null, 0));
        return previous;
    }

    private void writeState(Path installRoot, String appId, UpgradeState state) {
        try {
            Path dir = installRoot.resolve(".upgrade");
            Files.createDirectories(dir);
            Files.write(dir.resolve(appId + ".json"), MAPPER.writeValueAsBytes(state));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private UpgradeState readState(Path installRoot, String appId) {
        Path file = installRoot.resolve(".upgrade").resolve(appId + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return MAPPER.readValue(Files.readAllBytes(file), UpgradeState.class);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** The result of a preflight check. */
    public record UpgradeReport(boolean compatible, String appId, String fromVersion,
            String toVersion, List<String> messages) {
    }

    /** The result of an upgrade. */
    public record UpgradeResult(String appId, String fromVersion, String toVersion, boolean canary) {
    }

    /** A staged canary candidate and the percentage of traffic it should receive. */
    public record CanaryStatus(InstalledApp candidate, int weightPercent) {
    }

    /** Persisted snapshot for rollback/promotion (previous active, staged candidate, canary weight). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UpgradeState(InstalledApp previous, InstalledApp candidate, int canaryWeight) {
    }
}
