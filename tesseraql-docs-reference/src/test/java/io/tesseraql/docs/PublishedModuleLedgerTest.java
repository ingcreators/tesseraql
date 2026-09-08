package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Every module this repository declares is on the README map, and every one a consumer can depend
 * on is managed by the BOM.
 *
 * <p>Both rosters were hand-maintained and both had decayed: the BOM omitted six coordinates and
 * the README omitted eight modules, while {@code docs/release.md} promised a consumer could
 * resolve any of them through the BOM. Nothing checked either — the rosters are claims the
 * repository publishes about itself, and only review enforced them.
 *
 * <p><b>The two predicates are different, and that is the point.</b> The BOM exempts three
 * coordinates on delivery mechanics: it cannot manage itself; a Maven plugin's version comes from
 * {@code pluginManagement} inherited through {@code <parent>}, so a {@code dependencyManagement}
 * entry for it would be inert; and one module opts out of publishing, so a managed version would
 * name a coordinate that is not on Central. The README exempts <em>nothing</em> — it already
 * names the BOM and the plugin, neither a consumable library, so its rule is "a module in this
 * repository", and an exemption-free rule is the only one that cannot silently exempt a module
 * added next year.
 *
 * <p>{@code packaging: pom} is deliberately not an exemption. A future aggregator goes red here
 * and gets a decision rather than vanishing from both lists.
 *
 * <p><b>Three things this guard must not do</b>, each of which was built and run before it was
 * written:
 *
 * <ul>
 * <li>Derive the module set from a directory listing. A working checkout has 32
 * {@code tesseraql-*} directories against 29 modules — three survive the Camel removal holding
 * only {@code target/} — so a listing guard skips them by accident, not by design, and would
 * demand rosters for modules deleted a campaign ago. The set comes from {@code <modules>}, and a
 * module whose pom is missing throws rather than being skipped into a silent exemption.
 * <li>Match a name as a bare substring. {@code tesseraql-studio} is a proper prefix of
 * {@code tesseraql-studio-runtime} — the only such pair among the 29 — so both sides match on a
 * delimiter: the BOM by DOM element text, the README by the backticked token. Unbackticked,
 * {@code tesseraql-cli} occurs three times in the quick start, so a bare search there passes with
 * the row deleted.
 * <li>Read the BOM as text. A window between the {@code dependencyManagement} tags still contains
 * an {@code <exclusion>}'s {@code artifactId}, which is a grandchild; the direct-child walk below
 * structurally cannot see one.
 * </ul>
 */
class PublishedModuleLedgerTest {

    private static final Path REPO = Path.of("..");
    private static final String GROUP = "io.tesseraql";

    @Test
    void everyPublishableModuleIsManagedByTheBom() throws Exception {
        List<String> modules = modules();
        Set<String> exempt = bomExempt(modules);

        // The exemption set is asserted before it is used. A predicate written the natural way
        // round — "exempt anything whose packaging is not jar" — makes all 29 exempt, because 27
        // of the poms declare no packaging element at all, and both methods then pass with every
        // omission live.
        assertThat(exempt)
                .as("modules a BOM cannot manage, derived from the poms rather than listed here")
                .containsExactlyInAnyOrder("tesseraql-bom", "tesseraql-docs-reference",
                        "tesseraql-maven-plugin");

        Set<String> managed = bomManaged();
        assertThat(managed).as("the BOM manages something at all").contains("tesseraql-core");
        assertThat(managed).as("only dependencyManagement entries count")
                .doesNotContain("tesseraql-parent", "tesseraql-bom");

        Set<String> expected = new TreeSet<>(modules);
        expected.removeAll(exempt);
        assertThat(managed)
                .as("every module a consumer can depend on is managed by the BOM, and the BOM"
                        + " manages nothing that is not a module. docs/release.md promises a"
                        + " consumer can resolve any of them through it")
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    /** Presence is not enough: a literal version would resolve a stale artifact for a consumer. */
    @Test
    void everyManagedModuleIsAtTheProjectVersion() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (Element dependency : managedDependencies()) {
            if (GROUP.equals(childText(dependency, "groupId"))
                    && !"${project.version}".equals(childText(dependency, "version"))) {
                wrong.add(childText(dependency, "artifactId") + " at "
                        + childText(dependency, "version"));
            }
        }

        assertThat(wrong)
                .as("first-party coordinates managed at a literal version; a consumer importing"
                        + " the BOM would resolve a version this build did not produce")
                .isEmpty();
    }

    @Test
    void everyModuleIsNamedOnTheReadmeMap() throws Exception {
        List<String> modules = modules();
        String section = readmeModuleSection();

        // Vacuity first: an empty or whole-file window makes the loop below meaningless in
        // opposite directions — empty fails for all 29 and looks like a real failure, whole-file
        // passes forever.
        assertThat(section).as("the README module section").isNotBlank()
                .contains("| Module | Purpose |")
                .doesNotContain("## Java policy");

        List<String> missing = new ArrayList<>();
        for (String module : modules) {
            if (!section.contains("`" + module + "`")) {
                missing.add(module);
            }
        }

        assertThat(missing)
                .as("modules this repository declares but the README map does not name. The map"
                        + " is the one roster a newcomer reads; it exempts nothing, because a"
                        + " rule with exemptions silently exempts the next module too")
                .isEmpty();
    }

    /** The reactor's declared modules: the direct {@code <module>} children of {@code <modules>}. */
    private static List<String> modules() throws Exception {
        Element project = parse(REPO.resolve("pom.xml"));
        Element modules = first(project, "modules");
        assertThat(modules).as("the root pom declares <modules>").isNotNull();

        List<String> names = new ArrayList<>();
        for (Node child = modules.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && "module".equals(element.getLocalName())) {
                names.add(element.getTextContent().trim());
            }
        }

        assertThat(names).as("declared modules")
                .contains("tesseraql-core", "tesseraql-maven-plugin")
                .hasSizeGreaterThan(20);
        return names;
    }

    /**
     * Coordinates a BOM cannot manage, each derived from the module's own pom.
     *
     * <p>A module named in {@code <modules>} whose pom is missing throws out of {@link #parse}
     * rather than being skipped: a skip turns a mistyped module name into an exemption nobody
     * decided on.
     */
    private static Set<String> bomExempt(List<String> modules) throws Exception {
        Set<String> exempt = new TreeSet<>();
        for (String module : modules) {
            Element project = parse(REPO.resolve(module).resolve("pom.xml"));
            String artifactId = childText(project, "artifactId");
            Element properties = first(project, "properties");
            boolean skipsPublishing = "true".equals(childText(properties, "skipPublishing"))
                    || "true".equals(childText(properties, "maven.deploy.skip"));
            // Exact equality, never contains: tesseraql-docs-reference declares an
            // exec-maven-plugin, whose artifactId contains "maven-plugin".
            if ("tesseraql-bom".equals(artifactId)
                    || "maven-plugin".equals(childText(project, "packaging"))
                    || skipsPublishing) {
                exempt.add(module);
            }
        }
        return exempt;
    }

    private static Set<String> bomManaged() throws Exception {
        Set<String> managed = new LinkedHashSet<>();
        for (Element dependency : managedDependencies()) {
            if (GROUP.equals(childText(dependency, "groupId"))) {
                managed.add(childText(dependency, "artifactId"));
            }
        }
        return managed;
    }

    /**
     * The {@code <dependency>} elements inside {@code <dependencyManagement><dependencies>}, by
     * direct descent. This is what makes an {@code <exclusion>}'s artifactId — a grandchild —
     * structurally invisible, and what keeps the BOM's own and its parent's coordinates out
     * without an exemption for either.
     */
    private static List<Element> managedDependencies() throws Exception {
        Element project = parse(REPO.resolve("tesseraql-bom").resolve("pom.xml"));
        Element management = first(project, "dependencyManagement");
        assertThat(management).as("the BOM declares <dependencyManagement>").isNotNull();
        Element dependencies = first(management, "dependencies");
        assertThat(dependencies).as("the BOM's dependencyManagement declares <dependencies>")
                .isNotNull();

        List<Element> found = new ArrayList<>();
        for (Node child = dependencies.getFirstChild(); child != null; child = child
                .getNextSibling()) {
            if (child instanceof Element element && "dependency".equals(element.getLocalName())) {
                found.add(element);
            }
        }
        return found;
    }

    /**
     * The README between the {@code ## Modules} heading and the next {@code ## } heading, scanned
     * by line.
     *
     * <p>Not {@code indexOf("## ", start)}: that matches the heading itself for an empty window,
     * and it also matches the {@code ### } headings elsewhere in the file. A missing heading fails
     * here rather than degrading to the whole file.
     */
    private static String readmeModuleSection() throws IOException {
        List<String> lines = Files.readAllLines(REPO.resolve("README.md"));
        int start = lines.indexOf("## Modules");
        assertThat(start).as("README.md has a '## Modules' heading").isNotNegative();

        StringBuilder section = new StringBuilder();
        for (int line = start + 1; line < lines.size(); line++) {
            if (lines.get(line).startsWith("## ")) {
                break;
            }
            section.append(lines.get(line)).append('\n');
        }
        return section.toString();
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
        if (parent == null) {
            return null;
        }
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
