package io.tesseraql.operations.app;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Upgrades an installed app with a preflight, snapshot, and rollback lifecycle (design ch. 31),
 * gated by the version compatibility matrix (ch. 30).
 *
 * <p>The CLI's side of the install root's deploy protocol (docs/runtime-replace.md structural
 * decision 2 and its addendum): every verb here writes a <em>candidate</em> into
 * {@code .upgrade/<name>.json} and nothing else. A direct upgrade writes a {@code replace}
 * candidate — the version to become the serving one; a canary upgrade stages a {@code canary}
 * candidate beside the serving version at a traffic weight; {@link #promote} rewrites the staged
 * canary as a replace candidate; {@link #rollback} writes the snapshotted previous version as
 * one, or clears a candidate that never applied. <b>The catalogue is the host's to move</b>: it
 * names the serving version and moves when a host applies a replace candidate, so it can never
 * name a version no host has started — a refused deploy used to land there and fail the next
 * cold start of the whole stack.
 */
public final class AppUpgrader {

    private static final TqlErrorCode INCOMPATIBLE = new TqlErrorCode(TqlDomain.UPGRADE, 4090);
    private static final TqlErrorCode NO_TARGET = new TqlErrorCode(TqlDomain.UPGRADE, 4091);
    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();
    private static final int DEFAULT_CANARY_WEIGHT = 10;

    /** A candidate staged beside the serving version at a traffic weight. */
    public static final String CANARY = "canary";
    /** A candidate to become the serving version; the host moves the catalogue when it does. */
    public static final String REPLACE = "replace";

    private final AppInstaller installer = new AppInstaller();

    /** Validates a candidate package against the current install and the framework version. */
    public UpgradeReport preflight(Path tqlapp, Path installRoot,
            SemanticVersion frameworkVersion) {
        AppInstaller.PackageInfo info = installer.peek(tqlapp);
        Optional<InstalledApp> current = new AppCatalog(installRoot).find(info.name());
        List<String> messages = new ArrayList<>();

        SemanticVersion to;
        try {
            to = SemanticVersion.parse(info.version());
        } catch (IllegalArgumentException ex) {
            messages.add("Package version is not a valid version: " + info.version());
            return new UpgradeReport(false, info.name(),
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
            messages.add("Package requires framework " + required + " but runtime is "
                    + frameworkVersion);
        }

        return new UpgradeReport(messages.isEmpty(), info.name(),
                current.map(InstalledApp::version).orElse(null), info.version(), messages);
    }

    /**
     * Writes the new version as a replace candidate, snapshotting the serving version for
     * rollback; a host applies it and moves the catalogue.
     */
    public UpgradeResult upgrade(Path tqlapp, Path installRoot, SemanticVersion frameworkVersion) {
        return upgrade(tqlapp, installRoot, frameworkVersion, false);
    }

    /**
     * Upgrades after verifying the package SHA-256 (design ch. 49, 50): a tampered or corrupted
     * package is rejected before the preflight even runs.
     */
    public UpgradeResult upgrade(Path tqlapp, Path installRoot, SemanticVersion frameworkVersion,
            boolean canary, String expectedSha256) {
        AppInstaller.verifyIntegrity(tqlapp, expectedSha256);
        return upgrade(tqlapp, installRoot, frameworkVersion, canary);
    }

    /**
     * Upgrades the app: the package is placed side by side and written as a candidate — staged
     * beside the serving version when {@code canary} is true ({@link #promote} activates it,
     * {@link #rollback} discards it), else to replace it. The preflight's floor and the
     * snapshotted {@code previous} are the catalogue's entry, which is always a version that
     * served.
     */
    public UpgradeResult upgrade(Path tqlapp, Path installRoot, SemanticVersion frameworkVersion,
            boolean canary) {
        UpgradeReport report = preflight(tqlapp, installRoot, frameworkVersion);
        if (!report.compatible()) {
            throw new TqlException(INCOMPATIBLE,
                    "Upgrade preflight failed: " + String.join("; ", report.messages()));
        }
        InstalledApp previous = new AppCatalog(installRoot).find(report.appName()).orElse(null);
        List<String> entitled = previous == null ? List.of() : previous.entitledTenants();

        InstalledApp placed = installer.place(tqlapp, installRoot, null, entitled);
        writeState(installRoot, report.appName(), canary
                ? new UpgradeState(previous, placed, DEFAULT_CANARY_WEIGHT, CANARY)
                : new UpgradeState(previous, placed, 0, REPLACE));
        return new UpgradeResult(report.appName(), report.fromVersion(), report.toVersion(),
                canary);
    }

    /** Adjusts the percentage of traffic the staged canary candidate should receive (0-100). */
    public void setCanaryWeight(String appName, Path installRoot, int weightPercent) {
        UpgradeState state = readState(installRoot, appName);
        if (state == null || !state.isCanary()) {
            throw new TqlException(NO_TARGET, "No staged candidate for app: " + appName);
        }
        int weight = Math.max(0, Math.min(100, weightPercent));
        writeState(installRoot, appName,
                new UpgradeState(state.previous(), state.candidate(), weight, CANARY));
    }

    /** The staged canary candidate and its traffic weight, if a canary is in progress. */
    public Optional<CanaryStatus> canary(String appName, Path installRoot) {
        UpgradeState state = readState(installRoot, appName);
        if (state == null || !state.isCanary()) {
            return Optional.empty();
        }
        return Optional.of(new CanaryStatus(state.candidate(), state.canaryWeight()));
    }

    /**
     * The replace candidate — a direct deploy, a promote or a rollback the host has not applied
     * yet, or one it has (the catalogue then names it; the reconciler reads the two together).
     */
    public Optional<InstalledApp> pending(String appName, Path installRoot) {
        UpgradeState state = readState(installRoot, appName);
        if (state == null || state.candidate() == null || state.isCanary()) {
            return Optional.empty();
        }
        return Optional.of(state.candidate());
    }

    /**
     * Rewrites a staged canary as a replace candidate; a host promotes the canary it already
     * runs (nothing starts) and moves the catalogue.
     */
    public InstalledApp promote(String appName, Path installRoot) {
        UpgradeState state = readState(installRoot, appName);
        if (state == null || !state.isCanary()) {
            throw new TqlException(NO_TARGET, "No staged candidate to promote for app: " + appName);
        }
        writeState(installRoot, appName,
                new UpgradeState(state.previous(), state.candidate(), 0, REPLACE));
        return state.candidate();
    }

    /**
     * Reverts the last upgrade. A candidate the host has not applied — a staged canary, or a
     * replace candidate the catalogue does not name — is discarded and the serving version
     * stays. An applied one is reverted by writing the snapshotted previous version as the
     * replace candidate; its files must still be present, and it is the version that served
     * before, because the catalogue only ever named served versions.
     */
    public InstalledApp rollback(String appName, Path installRoot) {
        UpgradeState state = readState(installRoot, appName);
        if (state == null) {
            throw new TqlException(NO_TARGET, "Nothing to roll back for app: " + appName);
        }
        InstalledApp serving = new AppCatalog(installRoot).find(appName).orElse(null);
        if (state.candidate() != null && (serving == null
                || !serving.version().equals(state.candidate().version()))) {
            // Never applied: discard the candidate and keep what serves.
            writeState(installRoot, appName, new UpgradeState(state.previous(), null, 0, null));
            return serving != null ? serving : state.previous();
        }
        InstalledApp previous = state.previous();
        if (previous == null
                || (serving != null && previous.version().equals(serving.version()))) {
            throw new TqlException(NO_TARGET,
                    "No previous version to roll back to for app: " + appName);
        }
        if (!Files.isDirectory(installRoot.resolve(previous.path()))) {
            throw new TqlException(NO_TARGET,
                    "Previous version files are missing for app: " + appName);
        }
        writeState(installRoot, appName, new UpgradeState(null, previous, 0, REPLACE));
        return previous;
    }

    private void writeState(Path installRoot, String appName, UpgradeState state) {
        try {
            Path dir = installRoot.resolve(".upgrade");
            Files.createDirectories(dir);
            // Atomic on purpose (docs/runtime-replace.md): fine while only boot read this file
            // once, not fine once a running host's reconciler reads it concurrently.
            io.tesseraql.core.files.AtomicFiles.replace(dir.resolve(appName + ".json"),
                    MAPPER.writeValueAsBytes(state));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private UpgradeState readState(Path installRoot, String appName) {
        Path file = installRoot.resolve(".upgrade").resolve(appName + ".json");
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
    public record UpgradeReport(boolean compatible, String appName, String fromVersion,
            String toVersion, List<String> messages) {
    }

    /** The result of an upgrade. */
    public record UpgradeResult(String appName, String fromVersion, String toVersion,
            boolean canary) {
    }

    /** A staged canary candidate and the percentage of traffic it should receive. */
    public record CanaryStatus(InstalledApp candidate, int weightPercent) {
    }

    /**
     * Persisted intent (docs/runtime-replace.md structural decision 2's addendum): the previous
     * served version for rollback, the candidate, its canary weight, and the candidate's
     * {@code mode} — {@link #CANARY} or {@link #REPLACE}. A file with a candidate and no mode is
     * a canary, which is what every file written before the mode existed meant.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UpgradeState(InstalledApp previous, InstalledApp candidate, int canaryWeight,
            String mode) {

        boolean isCanary() {
            return candidate != null && !REPLACE.equals(mode);
        }
    }
}
