package io.tesseraql.apptasks;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * What {@code tesseraql-runtime} carries on its own classpath, as the runtime's build wrote it
 * into the runtime jar (docs/module-channel.md decision 9): the {@code dependency:list} output
 * of its runtime-scope closure at {@link #RESOURCE}. The module resolver excludes every artifact
 * named here from a declared module's closure — a module loads in a child of the runtime's
 * loader, parent-first, so a copy of any of these in {@code work/modules} never loads — and
 * packaging refuses a lock that still names one.
 *
 * <p>The ledger is read, never derived: the classpath a resolver runs on differs on each route
 * (the dist shaded jar, the reactor, the Maven plugin) and none of them is the classpath a
 * module meets in a deployment, whereas the runtime's own closure is the parent every deployment
 * shape gives the module loader. So the CLI reads the file from its classpath
 * ({@link #fromClasspath()}) and the Maven plugin, which carries no runtime, reads it from the
 * runtime artifact it resolves ({@link #fromJar(Path)}). A missing ledger is refused, never an
 * empty set: an empty set would resolve a closure the runtime's loader then shadows.
 */
public final class RuntimeClosure {

    /** Where the runtime's build writes the ledger inside the runtime jar. */
    public static final String RESOURCE = "META-INF/tesseraql/runtime-closure.txt";

    /** The section of {@code dependency:list} output whose entries are the closure. */
    private static final String RESOLVED = "The following files have been resolved:";

    private final SortedMap<String, String> versions;

    private RuntimeClosure(SortedMap<String, String> versions) {
        this.versions = Collections.unmodifiableSortedMap(versions);
    }

    /**
     * The ledger on this process's classpath — the runtime jar's, or {@code target/classes} in
     * the reactor. Refuses when there is none: a {@code tesseraql-runtime} built before the ledger
     * existed, which is a stale jar to rebuild, not a runtime that carries nothing.
     */
    public static RuntimeClosure fromClasspath() {
        URL url = RuntimeClosure.class.getClassLoader().getResource(RESOURCE);
        if (url == null) {
            throw new IllegalStateException("The runtime closure ledger " + RESOURCE
                    + " is not on the classpath: tesseraql-runtime's build writes it, so this"
                    + " process runs against a tesseraql-runtime built before the ledger existed"
                    + " — rebuild it (./mvnw -pl tesseraql-runtime -am install)");
        }
        try (InputStream in = url.openStream()) {
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read " + url, ex);
        }
    }

    /** The ledger inside a runtime jar — what a build without the runtime on its classpath reads. */
    public static RuntimeClosure fromJar(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            ZipEntry entry = file.getEntry(RESOURCE);
            if (entry == null) {
                throw new IllegalStateException(jar + " carries no " + RESOURCE
                        + ": a tesseraql-runtime built before the ledger existed");
            }
            try (InputStream in = file.getInputStream(entry)) {
                return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read " + RESOURCE + " from " + jar, ex);
        }
    }

    /**
     * Parses {@code dependency:list} output: a section header on its own line, then one indented
     * entry per artifact — {@code group:artifact:type[:classifier]:version:scope}, with the
     * module name the plugin appends after {@code " -- "} — and blank lines between sections.
     * Every entry must sit under the resolved section and read as a coordinate; anything else
     * is refused, so a plugin whose output changed shape fails here rather than excluding
     * nothing.
     */
    public static RuntimeClosure parse(String text) {
        SortedMap<String, String> versions = new TreeMap<>();
        String section = null;
        for (String raw : text.split("\r?\n")) {
            if (raw.isBlank()) {
                continue;
            }
            if (!Character.isWhitespace(raw.charAt(0))) {
                section = raw.strip();
                continue;
            }
            if (!RESOLVED.equals(section)) {
                throw new IllegalStateException("The runtime closure ledger lists an artifact"
                        + " outside its resolved section ('" + section + "'): " + raw.strip());
            }
            String line = raw.strip();
            int suffix = line.indexOf(" -- ");
            String coordinate = suffix < 0 ? line : line.substring(0, suffix);
            String[] parts = coordinate.split(":");
            if (parts.length < 5 || parts.length > 6) {
                throw new IllegalStateException("The runtime closure ledger holds a line that is"
                        + " not a group:artifact:type[:classifier]:version:scope coordinate: "
                        + line);
            }
            versions.put(parts[0] + ":" + parts[1], parts[parts.length - 2]);
        }
        if (versions.isEmpty()) {
            throw new IllegalStateException("The runtime closure ledger names no artifact");
        }
        return new RuntimeClosure(versions);
    }

    /** Whether the runtime carries {@code group:artifact}, at any version. */
    public boolean carries(String groupArtifact) {
        return versions.containsKey(groupArtifact);
    }

    /** The version the runtime carries {@code group:artifact} at, when it does. */
    public Optional<String> version(String groupArtifact) {
        return Optional.ofNullable(versions.get(groupArtifact));
    }

    /** Every {@code group:artifact} the runtime carries, sorted. */
    public Set<String> artifacts() {
        return versions.keySet();
    }
}
