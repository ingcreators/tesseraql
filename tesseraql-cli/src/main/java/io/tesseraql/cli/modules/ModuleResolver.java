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
 * Resolves the declared {@code tesseraql.modules} set — and its full compile+runtime closure — from
 * Maven repositories, with versions supplied by the TesseraQL BOM (design: app-developer-distribution
 * work item 4). It embeds the ShrinkWrap Maven resolver, so no Maven install is needed and the
 * resolution honors {@code ~/.m2/settings.xml} (proxies, mirrors, credentials) automatically.
 *
 * <p>The declared coordinates are written into a synthetic POM that imports the BOM, so an
 * unversioned {@code group:artifact} picks up the BOM-managed version; the resolver then collects
 * the transitive closure (the same closure the build's {@code copy-dependencies} produces for the
 * bundled codecs). Each resolved artifact is returned with its SHA-256 for {@code modules.lock}.
 */
public final class ModuleResolver {

    private final String bomCoordinate;
    private final boolean offline;

    public ModuleResolver(String bomCoordinate) {
        this(bomCoordinate, false);
    }

    public ModuleResolver(String bomCoordinate, boolean offline) {
        this.bomCoordinate = bomCoordinate;
        this.offline = offline;
    }

    /** Resolves the closure of {@code declared}, sorted by coordinate for a stable lock/classpath. */
    public List<ResolvedModule> resolve(List<ModuleCoordinate> declared) {
        if (declared.isEmpty()) {
            return List.of();
        }
        Path pom = writePom(declared);
        try {
            MavenResolvedArtifact[] artifacts = Maven.configureResolver()
                    .workOffline(offline)
                    .loadPomFromFile(pom.toFile())
                    .importCompileAndRuntimeDependencies()
                    .resolve()
                    .withTransitivity()
                    .asResolvedArtifact();
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

    /**
     * Writes a synthetic POM declaring the module coordinates. The BOM is imported only when some
     * coordinate omits its version (so the BOM supplies it); fully-pinned sets resolve without it.
     */
    private Path writePom(List<ModuleCoordinate> declared) {
        StringBuilder dependencies = new StringBuilder();
        boolean needsBom = false;
        for (ModuleCoordinate coordinate : declared) {
            needsBom |= !coordinate.hasVersion();
            dependencies.append("    <dependency><groupId>").append(coordinate.groupId())
                    .append("</groupId><artifactId>").append(coordinate.artifactId())
                    .append("</artifactId>");
            if (coordinate.hasVersion()) {
                dependencies.append("<version>").append(coordinate.version()).append("</version>");
            }
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
