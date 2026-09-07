package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Every plugin that runs in this build has a version this repository chose.
 *
 * <p>A plugin declared with no {@code <version>} and managed nowhere takes whatever the running
 * Maven supplies, and this repository runs two: the wrapper's 3.9.16 and the 3.8.7 the devcontainer
 * installs and that AGENTS.md, CONTRIBUTING.md and docs/build.md all tell a contributor to invoke
 * as {@code mvn}. They do not agree — {@code maven-dependency-plugin} is 3.7.0 against 2.8 and
 * {@code maven-resources-plugin} is 3.4.0 against 2.6 — and those two are the ones
 * {@code tesseraql-yaml} uses to unpack the Hypermedia Components WebJAR and copy its email
 * templates into the jar. They write bytes that ship.
 *
 * <p>That is the same claim {@code project.build.outputTimestamp} makes, one layer down: a
 * reproducible archive built by a plugin whose version depends on which Maven you happened to type
 * is not reproducible. So this ledger also holds the property itself, which #1215 shipped with
 * nothing that fails if it is deleted.
 *
 * <p><b>The predicate is "unresolved", not "has no {@code <version>}".</b> Eleven declarations in
 * the reactor carry no version and are perfectly honest, because the root
 * {@code <pluginManagement>} supplies one. Asserting on the element alone would be red on thirteen
 * POMs, eleven of them for no reason, which is a guard nobody keeps.
 *
 * <p>It cannot see a plugin nothing declares. {@code maven-help-plugin} is resolved by prefix from
 * the command line at five tag-path invocations and appears in no POM at all; it is pinned in
 * {@code pluginManagement} beside these, and the pin was rehearsed rather than assumed.
 */
class PluginVersionLedgerTest {

    private static final Path REPO = Path.of("..");
    private static final String POM_NS = "http://maven.apache.org/POM/4.0.0";

    @Test
    void everyDeclaredPluginResolvesToAVersionThisRepositoryChose() throws Exception {
        Set<String> managed = managedByTheRoot();
        assertThat(managed)
                .as("the root pluginManagement could not be read; this guard would pass vacuously")
                .isNotEmpty();

        List<String> unresolved = new ArrayList<>();
        for (Path pom : poms()) {
            Element project = parse(pom);
            Set<String> locallyManaged = new TreeSet<>();
            for (Element plugin : pluginsUnder(project, "pluginManagement")) {
                locallyManaged.add(text(plugin, "artifactId"));
            }
            for (Element plugin : pluginsUnder(project, "plugins")) {
                String artifact = text(plugin, "artifactId");
                if (artifact == null || text(plugin, "version") != null
                        || managed.contains(artifact) || locallyManaged.contains(artifact)) {
                    continue;
                }
                unresolved.add(REPO.relativize(pom).toString().replace('\\', '/')
                        + ": " + artifact);
            }
        }

        assertThat(unresolved)
                .as("plugins whose version neither the declaration nor the root pluginManagement "
                        + "supplies, so the running Maven picks it; pin each in the root "
                        + "pluginManagement with a property, as the others are")
                .isEmpty();
    }

    /**
     * The repository's own scripts run the wrapper, and the devcontainer ships no second Maven.
     *
     * <p>The root {@code requireMavenVersion} rule is the real guard — it fails a build run by
     * 3.8.7 with {@code Detected Maven Version: 3.8.7 is not in the allowed range [3.9.16,)}, which
     * was rehearsed rather than assumed. These two assertions cover the places that produced the
     * damage rather than the rule: {@code scripts/run-ci-local.sh}, whose whole purpose is to
     * reproduce CI, invoked the Maven CI never uses, and {@code scripts/verify-dev-env.sh} printed
     * 3.8.7's version and then said the environment looked ready.
     *
     * <p>Deliberately narrow. It reads {@code scripts/} and the devcontainer image, not prose: the
     * CHANGELOG records past commands verbatim and must keep saying what was true then, and an
     * application developer's own build is theirs to invoke.
     */
    @Test
    void theRepositorysOwnBuildEntryPointsUseTheWrapper() throws IOException {
        List<String> bare = new ArrayList<>();
        try (Stream<Path> scripts = Files.list(REPO.resolve("scripts"))) {
            for (Path script : scripts.filter(Files::isRegularFile).sorted().toList()) {
                List<String> lines = Files.readAllLines(script);
                for (int line = 0; line < lines.size(); line++) {
                    String text = lines.get(line).strip();
                    if (text.startsWith("#")) {
                        continue;
                    }
                    if (text.matches(".*(^|[^./\\w-])mvn\\s.*")) {
                        bare.add("scripts/" + script.getFileName() + ":" + (line + 1) + " " + text);
                    }
                }
            }
        }
        assertThat(bare)
                .as("scripts invoking a Maven off the PATH rather than ./mvnw; the wrapper pins "
                        + "3.9.16 and the build now refuses anything older")
                .isEmpty();

        String dockerfile = Files.readString(REPO.resolve(".devcontainer/Dockerfile"));
        assertThat(dockerfile.lines().map(String::strip).toList())
                .as("the devcontainer must not install a second Maven; the wrapper is the one the "
                        + "build uses, and an apt maven on the PATH is how the wrong one gets run")
                .doesNotContain("maven \\", "maven");
    }

    /**
     * Anywhere the wrapper runs on a POSIX shell, {@code unzip} is on the PATH.
     *
     * <p>This is not a preference. {@code mvnw:178-182} chooses the distribution's archive format
     * by whether {@code unzip} exists: without it, it rewrites the URL from {@code .zip} to
     * {@code .tar.gz} and downloads a <b>different file</b>. Since
     * {@code distributionSha256Sum} is a single value, it can only ever match one of the two, and
     * the mismatch is reported as "your Maven distribution might be compromised" — an alarming
     * message for a benign cause.
     *
     * <p>It cost a red build to find. The checksum was rehearsed in both directions on a machine
     * that has {@code unzip}, and the two {@code maven:} base images that run {@code ./mvnw} do
     * not — they ship {@code tar} only. The extraction branch at {@code mvnw:252-257} keys on the
     * same test, so pointing the URL at the {@code .tar.gz} instead would break every machine that
     * <em>does</em> have {@code unzip}. Ensuring the tool is the only stable answer.
     *
     * <p>{@code mvnw.cmd} always uses {@code Expand-Archive} on the {@code .zip} and has no such
     * branch, so the pinned checksum is the zip's.
     */
    @Test
    void everyDockerfileThatRunsTheWrapperInstallsUnzip() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Path dockerfile : dockerfiles()) {
            String text = Files.readString(dockerfile);
            if (!text.contains("./mvnw")) {
                continue;
            }
            if (!text.contains("unzip")) {
                missing.add(REPO.relativize(dockerfile).toString().replace('\\', '/'));
            }
        }

        assertThat(missing)
                .as("Dockerfiles that run ./mvnw without unzip on the PATH; the wrapper then "
                        + "fetches the .tar.gz distribution instead of the .zip and fails the "
                        + "checksum with a compromise warning (mvnw:178-182)")
                .isEmpty();
    }

    /** Every tracked Dockerfile: the devcontainer's and the deployment images'. */
    private static List<Path> dockerfiles() throws IOException {
        List<Path> found = new ArrayList<>();
        for (String directory : List.of(".devcontainer", "deploy")) {
            Path dir = REPO.resolve(directory);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().startsWith("Dockerfile"))
                        .sorted()
                        .forEach(found::add);
            }
        }
        assertThat(found).as("no Dockerfiles found; this guard would pass vacuously").isNotEmpty();
        return found;
    }

    /** The reproducibility property #1215 shipped, which nothing else fails without. */
    @Test
    void theBuildStampsAReproducibleOutputTimestamp() throws Exception {
        Element root = parse(REPO.resolve("pom.xml"));
        assertThat(childText(first(root, "properties"), "project.build.outputTimestamp"))
                .as("project.build.outputTimestamp is what makes two builds of one source produce "
                        + "identical archives; without it the archiver stamps the wall clock")
                .isNotBlank();
    }

    private static Set<String> managedByTheRoot() throws Exception {
        Set<String> managed = new TreeSet<>();
        for (Element plugin : pluginsUnder(parse(REPO.resolve("pom.xml")), "pluginManagement")) {
            managed.add(text(plugin, "artifactId"));
        }
        return managed;
    }

    /**
     * The root POM and every module's, listed rather than walked: a walk from {@code ..} would
     * read the worktrees under {@code .claude/}, and {@code target/} copies besides.
     */
    private static List<Path> poms() throws IOException {
        List<Path> poms = new ArrayList<>();
        poms.add(REPO.resolve("pom.xml"));
        try (Stream<Path> children = Files.list(REPO)) {
            children.filter(path -> path.getFileName().toString().startsWith("tesseraql-"))
                    .map(path -> path.resolve("pom.xml"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(poms::add);
        }
        return poms;
    }

    /**
     * Every {@code <plugin>} inside a {@code <section>} anywhere in the document, profiles
     * included: a plugin declared only under a profile still runs when that profile is active.
     */
    private static List<Element> pluginsUnder(Element project, String section) {
        List<Element> plugins = new ArrayList<>();
        NodeList sections = project.getElementsByTagNameNS(POM_NS, section);
        for (int i = 0; i < sections.getLength(); i++) {
            NodeList found = ((Element) sections.item(i)).getElementsByTagNameNS(POM_NS, "plugin");
            for (int j = 0; j < found.getLength(); j++) {
                Element plugin = (Element) found.item(j);
                // A <plugins> nested inside <pluginManagement> is management, not a declaration.
                if (section.equals("plugins") && insideManagement(plugin)) {
                    continue;
                }
                plugins.add(plugin);
            }
        }
        return plugins;
    }

    private static boolean insideManagement(Node node) {
        for (Node parent = node.getParentNode(); parent != null; parent = parent.getParentNode()) {
            if ("pluginManagement".equals(parent.getLocalName())) {
                return true;
            }
        }
        return false;
    }

    /** A direct child's text, so a nested {@code <configuration>} cannot answer for its parent. */
    private static String text(Element plugin, String name) {
        return childText(plugin, name);
    }

    private static String childText(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getLocalName())) {
                return element.getTextContent().trim();
            }
        }
        return null;
    }

    private static Element first(Element parent, String name) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static Element parse(Path pom) throws IOException, SAXException,
            ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
    }
}
