package io.tesseraql.cli.modules;

import io.tesseraql.core.util.Hashing;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.jboss.shrinkwrap.resolver.api.maven.MavenResolvedArtifact;
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinate;

/**
 * Resolves the declared {@code tesseraql.modules} set — and its full compile+runtime closure, less
 * the framework's own artifacts ({@link #FRAMEWORK_GROUP}) — from Maven repositories, with versions
 * supplied by the TesseraQL BOM (design: app-developer-distribution work item 4). It embeds the
 * ShrinkWrap Maven resolver, so no Maven install is needed and the resolution honors
 * {@code ~/.m2/settings.xml} (proxies, mirrors, credentials) automatically.
 *
 * <p>The declared coordinates are written into a synthetic POM that imports the BOM, so an
 * unversioned {@code group:artifact} picks up the BOM-managed version; the resolver then collects
 * the transitive closure (the same closure the build's {@code copy-dependencies} produces for the
 * bundled codecs). Each resolved artifact is returned with its SHA-256 for {@code modules.lock}.
 */
public final class ModuleResolver {

    private final String bomCoordinate;
    private final boolean offline;
    private final boolean alwaysImportBom;

    public ModuleResolver(String bomCoordinate) {
        this(bomCoordinate, false);
    }

    public ModuleResolver(String bomCoordinate, boolean offline) {
        this(bomCoordinate, offline, false);
    }

    /**
     * With {@code alwaysImportBom}, the synthetic POM imports the BOM even when every declared
     * coordinate carries its version — what {@code modules fetch} needs, because the bag it fills
     * has to contain the BOM for a later resolve whose declaration omits one
     * (docs/module-channel.md decision 5).
     */
    public ModuleResolver(String bomCoordinate, boolean offline, boolean alwaysImportBom) {
        this.bomCoordinate = bomCoordinate;
        this.offline = offline;
        this.alwaysImportBom = alwaysImportBom;
    }

    /**
     * TQL-APP-4221: a declared module could not be resolved — the artifact, its version through
     * the BOM, or the BOM itself is not in any repository this resolver reaches. A refusal in
     * one line naming what and where to look, exit 2, in place of the resolver's own stack
     * trace (docs/codec-discovery.md decision 5).
     */
    static final String UNRESOLVABLE = "TQL-APP-4221";

    /** Resolves the closure of {@code declared}, sorted by coordinate for a stable lock/classpath. */
    public List<ResolvedModule> resolve(List<ModuleCoordinate> declared) {
        if (declared.isEmpty()) {
            return List.of();
        }
        Path pom = writePom(declared);
        try {
            MavenResolvedArtifact[] artifacts;
            try {
                artifacts = Maven.configureResolver()
                        .workOffline(offline)
                        .loadPomFromFile(pom.toFile())
                        .importCompileAndRuntimeDependencies()
                        .resolve()
                        .withTransitivity()
                        .asResolvedArtifact();
            } catch (org.jboss.shrinkwrap.resolver.api.ResolutionException
                    | org.jboss.shrinkwrap.resolver.api.InvalidConfigurationFileException ex) {
                throw new io.tesseraql.cli.UsageRefusal(UNRESOLVABLE + ": cannot resolve the"
                        + " declared modules " + declared.stream().map(ModuleCoordinate::toString)
                                .toList()
                        + (offline ? " offline" : "") + " - " + firstLine(ex.getMessage())
                        + "\n  Versions come from " + bomCoordinate + ": from the monorepo,"
                        + " `./mvnw -DskipTests install` puts it and the modules in the local"
                        + " repository; offline, pass --repo <bag> from `tesseraql modules"
                        + " fetch`; otherwise check the repositories in ~/.m2/settings.xml.");
            }
            List<ResolvedModule> resolved = new ArrayList<>();
            for (MavenResolvedArtifact artifact : artifacts) {
                MavenCoordinate coordinate = artifact.getCoordinate();
                Path file = artifact.asFile().toPath();
                resolved.add(new ResolvedModule(
                        coordinate.getGroupId() + ":" + coordinate.getArtifactId() + ":"
                                + coordinate.getVersion(),
                        file, Hashing.sha256(file)));
            }
            resolved.sort(Comparator.comparing(ResolvedModule::coordinate));
            return resolved;
        } finally {
            try {
                Files.deleteIfExists(pom);
            } catch (IOException ignored) {
                // A leftover temp POM is harmless.
            }
        }
    }

    /** The resolver's message up to its first line break: the sentence, not the model dump. */
    private static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "the resolver gave no reason";
        }
        int end = message.indexOf('\n');
        return (end < 0 ? message : message.substring(0, end)).strip();
    }

    /**
     * The framework's own group, excluded from every declared module's closure. A module compiles
     * against {@code tesseraql-core} (a codec, a function) or {@code tesseraql-yaml} (a blob-store
     * provider), and the runtime that loads the module already carries both: its loader is a child
     * of the runtime's, parent-first, so a framework jar in {@code work/modules} is never the one
     * that loads. Copying it there was a second copy of core in every resolved cache, every
     * package and every bag, and — the day a module is built against a different core — a cache
     * and a lock claiming a framework version that does not run (docs/codec-discovery.md S5).
     *
     * <p>The rule this states: a module's {@code io.tesseraql} dependencies are the framework's,
     * and an application declares each module it uses by its own coordinate. The exclusion is
     * written into the synthetic POM rather than filtered from the result, so an offline
     * resolution never asks a bag for a framework jar the bag was never told to carry.
     */
    static final String FRAMEWORK_GROUP = "io.tesseraql";

    /**
     * Writes a synthetic POM declaring the module coordinates. The BOM is imported only when some
     * coordinate omits its version (so the BOM supplies it); fully-pinned sets resolve without it.
     * Every dependency excludes {@link #FRAMEWORK_GROUP} transitively.
     */
    private Path writePom(List<ModuleCoordinate> declared) {
        StringBuilder dependencies = new StringBuilder();
        boolean needsBom = alwaysImportBom;
        for (ModuleCoordinate coordinate : declared) {
            needsBom |= !coordinate.hasVersion();
            dependencies.append("    <dependency><groupId>").append(coordinate.groupId())
                    .append("</groupId><artifactId>").append(coordinate.artifactId())
                    .append("</artifactId>");
            if (coordinate.hasVersion()) {
                dependencies.append("<version>").append(coordinate.version()).append("</version>");
            }
            dependencies.append("<exclusions><exclusion><groupId>").append(FRAMEWORK_GROUP)
                    .append("</groupId><artifactId>*</artifactId></exclusion></exclusions>");
            dependencies.append("</dependency>\n");
        }
        String management = "";
        if (needsBom) {
            String[] bom = bomCoordinate.split(":");
            management = """
                      <dependencyManagement>
                        <dependencies>
                          <dependency>
                            <groupId>%s</groupId>
                            <artifactId>%s</artifactId>
                            <version>%s</version>
                            <type>pom</type>
                            <scope>import</scope>
                          </dependency>
                        </dependencies>
                      </dependencyManagement>
                    """.formatted(bom[0], bom[1], bom[2]);
        }
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>io.tesseraql.modules</groupId>
                  <artifactId>module-resolution</artifactId>
                  <version>0</version>
                  <packaging>pom</packaging>
                %s  <dependencies>
                %s  </dependencies>
                </project>
                """.formatted(management, dependencies);
        try {
            Path file = Files.createTempFile("tesseraql-modules-", ".xml");
            Files.writeString(file, pom);
            return file;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
