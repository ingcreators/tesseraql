package io.tesseraql.maven;

import io.tesseraql.apptasks.AppPackager;
import io.tesseraql.apptasks.PackagedModules;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import javax.inject.Inject;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

/**
 * Packages a TesseraQL app home into a deterministic {@code .tqlapp} archive
 * (design ch. 18 {@code package-app}, 32.3).
 *
 * <p>Like {@code tesseraql package}, the goal carries the application's declared
 * {@code tesseraql.modules} inside the archive (docs/module-channel.md decision 3). It resolves
 * them through Maven's own repository system rather than the CLI's embedded resolver, from the
 * exact coordinates {@code modules.lock} pins — no version ranges, no BOM, no transitive walk,
 * because the lock already settled all three. An application that declares modules without a lock
 * is refused (TQL-APP-4218), which is also the only route this goal has: the Maven plugin has no
 * command that writes a lock.
 */
@Mojo(name = "package-app", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public class PackageAppMojo extends AbstractMojo {

    /** The external app home to package. */
    @Parameter(property = "tesseraql.appHome", required = true)
    private File appHome;

    /** The build's generated-artifact directory; its {@code docs/} are merged into the package. */
    @Parameter(property = "tesseraql.generatedDir", defaultValue = "${project.build.directory}/tesseraql-generated")
    private File generatedDir;

    /** The output archive path. */
    @Parameter(property = "tesseraql.output", defaultValue = "${project.build.directory}/${project.artifactId}-${project.version}.tqlapp")
    private File output;

    /** Where the locked module closure is gathered before it enters the archive. */
    @Parameter(defaultValue = "${project.build.directory}/tesseraql-modules", readonly = true)
    private File modulesDir;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repositorySession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepositories;

    private final RepositorySystem repositorySystem;

    @Inject
    public PackageAppMojo(RepositorySystem repositorySystem) {
        this.repositorySystem = repositorySystem;
    }

    @Override
    public void execute() throws MojoExecutionException {
        try {
            Path home = appHome.toPath();
            Path modules = resolveDeclaredModules(home);
            new AppPackager().pack(home, new File(generatedDir, "docs").toPath(), modules,
                    output.toPath());
            // The sibling checksum lets installs verify package integrity (design ch. 49, 50).
            String sha256 = io.tesseraql.core.util.Hashing.sha256(output.toPath());
            Files.writeString(
                    output.toPath().resolveSibling(output.getName() + ".sha256"), sha256 + "\n");
            getLog().info("Packaged TesseraQL app to " + output + " (sha256 " + sha256 + ")");
        } catch (IOException ex) {
            throw new MojoExecutionException("Failed to package app", ex);
        }
    }

    /**
     * Fetches the locked closure into {@link #modulesDir} and returns it, or null when the
     * application declares no modules. Every jar is verified against the lock before it is
     * packaged, so a repository that served something else fails the build (TQL-APP-4219) instead
     * of producing an archive a deployment would trust.
     */
    private Path resolveDeclaredModules(Path home) throws MojoExecutionException, IOException {
        io.tesseraql.yaml.config.AppConfig config = new io.tesseraql.yaml.manifest.ManifestLoader()
                .load(home).config();
        Path lock = PackagedModules.requireLock(home, config).orElse(null);
        if (lock == null) {
            return null;
        }
        Path target = modulesDir.toPath();
        Files.createDirectories(target);
        for (Path stale : PackagedModules.jars(target)) {
            Files.delete(stale);
        }
        for (String coordinate : PackagedModules.lockedCoordinates(lock)) {
            Path jar = resolve(coordinate);
            Files.copy(jar, target.resolve(jar.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        PackagedModules.verifyAgainstLock(home, target, lock);
        return target;
    }

    /** One locked coordinate, from the local repository or the project's remotes. */
    private Path resolve(String coordinate) throws MojoExecutionException {
        try {
            ArtifactRequest request = new ArtifactRequest();
            request.setArtifact(new DefaultArtifact(coordinate));
            request.setRepositories(remoteRepositories);
            return repositorySystem.resolveArtifact(repositorySession, request)
                    .getArtifact().getFile().toPath();
        } catch (ArtifactResolutionException ex) {
            throw new MojoExecutionException("Cannot resolve locked module " + coordinate
                    + " — the lock names it, so the build cannot package without it", ex);
        }
    }
}
