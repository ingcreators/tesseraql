package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Drift guard against the Hypermedia Components kit (hc #manifest, exported since 0.1.9). The
 * framework bootstrap ({@code tesseraql.js}), the client message loader, and the documented htmx
 * contract ({@code docs/hypermedia-ui.md}) hard-reference kit behaviors, events, recipes, and
 * named module exports by name. If a future WebJar bump renames or drops one, pages break at
 * runtime with no compile error — this test fails the build instead. The version is resolved from
 * the classpath (same as {@link AssetRoutes}), so a clean bump keeps the guard green as
 * long as the symbols we depend on survive.
 */
class HypermediaComponentsManifestTest {

    private static final String WEBJAR = "hypermedia-components__core";

    /** Auto-installed behaviors the framework/docs contract depends on (bootstrap + shell). */
    private static final List<String> REQUIRED_BEHAVIORS = List.of(
            "installFieldErrors", // inline validation error distribution (ErrorResponseRenderer)
            "installToast", // HX-Trigger hc:toast notifications
            "installCsrfHeader", // X-CSRF-Token from the shell meta tag
            "installNavCurrent", // sidebar active-link marking (data-hc-nav-current)
            "installCopy", // share-URL copy buttons (data-hc-copy)
            "installConfirm", // data-hc-confirm gated actions
            "installCodeEditor", // live hc-code editor overlay (Studio 2-way SQL grammar)
            "installThemeToggle", // shell light/dark toggle (data-hc-theme-toggle)
            "installChatScroll", // copilot hc-chat transcript follows the stream
            "installDatagrid", // the view compiler's list/table markup (tql/view/table.html)
            "installDatagridActions", // IAM Admin bulk selection bar (users list)
            "installNetworkRetry", // the shell's no-answer Retry host (data-hc-network-retry)
            "installRemoteDialog", // Studio drawers, the lookup dialog, the conflict dialog
            "installCloseDialog", // close-on-success for remote-dialog compositions
            "installSessionExpiry", // the 401 re-login dialog's replay bridge (shell host)
            "installDirtyGuard", // the form view's unsaved-changes guard (data-hc-dirty-guard)
            "installSubmitOnChange", // the Studio flags page's switches post themselves
            "installInvokerCommands", // the dialog opener below the floor (commandfor fallback)
            "installCount", // the bounded text field's used / max output (data-hc-count)
            "installUploadProgress"); // the import page's and the deploy form's progress bar

    /** Custom events the framework listens for / documents. */
    private static final List<String> REQUIRED_EVENTS = List.of(
            "hc:confirmed", "hc:toast", "hc:copied", "hc:datagridsort",
            "hc:themechange", // the bootstrap persists it to the account preference
            "hc:sessionrenewed", // the bootstrap swaps the CSRF meta before the kit replays
            "hc:dirtychange"); // the unsaved-changes state flip apps may mirror to text

    /** Recipes the scaffolds emit and the hypermedia-ui/copilot/inbox contracts document. */
    private static final List<String> REQUIRED_RECIPES = List.of(
            "mutating-form", "field-errors", "data-region", "toast", "confirm-action",
            "chat-messages", "streaming-response", // the copilot panel (docs/copilot.md)
            "sse-updates", // the live inbox badge (docs/inbox.md)
            "datagrid-bulk-actions", // IAM Admin bulk disable (users list)
            "datagrid-bulk-errors", // the shared outcome report (tql/view/report.html)
            "csv-import", // the reviewed upload (tql/view/import.html, docs/csv-import.md)
            "file-upload", // its upload leg — the form, the progress bar, the 413
            "async-job", // its commit leg — the self-polling card (tql/view/job-card.html)
            "network-retry", // the shell's host follows this client contract
            "reference-lookup", // the lookup: field (docs/reference-lookup.md)
            "remote-dialog", // Studio drawers + the lookup search dialog
            "live-search", // the lookup dialog's search leg
            "session-expiry", // the 401 re-login dialog (ErrorResponseRenderer + shell host)
            "edit-conflict", // the declared lock's two faces (docs/edit-conflict.md)
            "unsaved-changes"); // the form view's dirty guard (tql/view/form.html)

    /** Named exports the framework imports from the behaviors bundle as an ES module. */
    private static final List<String> REQUIRED_BUNDLE_EXPORTS = List.of(
            "registerCodeLanguage", // tesseraql.js registers the tql-sql grammar
            "setMessages"); // ClientMessages layers the app catalog over the kit pack

    @Test
    void manifestDeclaresEveryBehaviorEventAndRecipeTheFrameworkDependsOn() throws Exception {
        JsonNode manifest = io.tesseraql.yaml.JsonMappers.constrained()
                .readTree(webjarResource("dist/manifest.json"));

        assertThat(names(manifest.get("behaviors"))).containsAll(REQUIRED_BEHAVIORS);
        assertThat(names(manifest.get("events"))).containsAll(REQUIRED_EVENTS);
        assertThat(names(manifest.get("recipes"))).containsAll(REQUIRED_RECIPES);
    }

    @Test
    void behaviorsBundleStillExportsTheSymbolsTheBootstrapImports() throws Exception {
        String bundle = new String(
                webjarResource("dist/hc.behaviors.min.js").readAllBytes(), StandardCharsets.UTF_8);

        assertThat(bundle).contains(REQUIRED_BUNDLE_EXPORTS);
    }

    /**
     * Every {@code --hc-*} token the framework stylesheet reads is one the kit defines or
     * reads itself (docs/audit-medium-leads.md slice 10, F96). {@code tesseraql.css} read three
     * names no kit release ever defined — {@code --hc-border}, {@code --hc-color-accent},
     * {@code --hc-radius-md} — so their fallbacks always won: a dark literal border in the light
     * theme, a fixed blue focus ring that ignored the accent axis, a 6px radius whatever the theme
     * set, while docs/hypermedia-ui.md promised "the framework hard-codes no color". A name the kit
     * reads with a fallback and never defines is the kit's own extension point (23 of them in
     * 0.4.0), so "defined or read" is the rule; a token the kit neither defines nor reads is a
     * phantom. Definitions inside CSS comments do not count — two of {@code hc.css}'s are
     * comment-only.
     */
    @Test
    void everyKitTokenTheFrameworkStylesheetReadsIsOneTheKitDefinesOrReads() throws Exception {
        String stylesheet = new String(HypermediaComponentsManifestTest.class.getClassLoader()
                .getResourceAsStream("tesseraql/assets/tesseraql.css").readAllBytes(),
                StandardCharsets.UTF_8);
        String kit = stripComments(new String(webjarResource("dist/hc.min.css").readAllBytes(),
                StandardCharsets.UTF_8))
                + stripComments(new String(webjarResource("dist/hc.tokens.css").readAllBytes(),
                        StandardCharsets.UTF_8));
        java.util.regex.Pattern read = java.util.regex.Pattern.compile("var\\((--hc-[a-z0-9-]+)");
        java.util.Set<String> defined = new java.util.TreeSet<>();
        java.util.regex.Matcher definitions = java.util.regex.Pattern
                .compile("(--hc-[a-z0-9-]+)\\s*:").matcher(kit);
        while (definitions.find()) {
            defined.add(definitions.group(1));
        }
        java.util.Set<String> readByKit = new java.util.TreeSet<>();
        java.util.regex.Matcher kitReads = read.matcher(kit);
        while (kitReads.find()) {
            readByKit.add(kitReads.group(1));
        }
        java.util.Set<String> used = new java.util.TreeSet<>();
        java.util.regex.Matcher uses = read.matcher(stripComments(stylesheet));
        while (uses.find()) {
            used.add(uses.group(1));
        }
        assertThat(used).as("tesseraql.css reads kit tokens").isNotEmpty();
        assertThat(defined).as("the kit defines tokens").isNotEmpty();

        List<String> phantoms = used.stream()
                .filter(name -> !defined.contains(name) && !readByKit.contains(name))
                .toList();
        assertThat(phantoms)
                .as("tokens tesseraql.css reads that the kit neither defines nor reads")
                .isEmpty();
    }

    /**
     * Every {@code data-tql-*} attribute the bootstrap reads is recorded in a document — the
     * htmx contract ({@code docs/hypermedia-ui.md}) or an upstream brief
     * ({@code docs/hc-briefs.md}) — so a hand-rolled behaviour is either contract or a named
     * stand-in awaiting the kit, never glue nobody wrote down (AGENTS.md rule 11;
     * docs/audit-low-leads.md slice 23, F102). Three were; hc 0.4.1 answered two (the dialog
     * opener became the platform's invoker command, submit-on-change the kit's behavior) and the
     * save hotkey remains, recorded as app policy.
     */
    @Test
    void everyBootstrapAttributeIsRecordedInTheDocs() throws Exception {
        String bootstrap = new String(HypermediaComponentsManifestTest.class.getClassLoader()
                .getResourceAsStream("tesseraql/assets/tesseraql.js").readAllBytes(),
                StandardCharsets.UTF_8);
        String docs = java.nio.file.Files.readString(
                java.nio.file.Path.of("..", "docs", "hypermedia-ui.md"), StandardCharsets.UTF_8)
                + java.nio.file.Files.readString(
                        java.nio.file.Path.of("..", "docs", "hc-briefs.md"),
                        StandardCharsets.UTF_8);
        // The attributes inside selector strings — what the bootstrap reads from the page.
        java.util.Set<String> read = new java.util.TreeSet<>();
        java.util.regex.Matcher selectors = java.util.regex.Pattern
                .compile("\"[^\"]*?(data-tql-[a-z-]+)[^\"]*?\"").matcher(bootstrap);
        while (selectors.find()) {
            read.add(selectors.group(1));
        }
        assertThat(read).as("tesseraql.js reads data-tql-* attributes").isNotEmpty();
        List<String> unrecorded = read.stream().filter(name -> !docs.contains(name)).toList();
        assertThat(unrecorded)
                .as("data-tql-* attributes the bootstrap reads that no document records")
                .isEmpty();
    }

    /**
     * The fill chain's rules in {@code tesseraql.css} and the classes the list page carries are
     * one list (docs/audit-low-leads.md slice 23, unfiled 25): a rule for a class no element
     * carries fills nothing, and a class no rule chains is the silent break the bulk-action form
     * was. The path itself — every wrapper on it a link — is the compiler test's.
     */
    @Test
    void theFillChainsRulesNameTheClassesTheListPageCarries() throws Exception {
        String css = stripComments(new String(HypermediaComponentsManifestTest.class
                .getClassLoader().getResourceAsStream("tesseraql/assets/tesseraql.css")
                .readAllBytes(), StandardCharsets.UTF_8));
        String page = new String(HypermediaComponentsManifestTest.class.getClassLoader()
                .getResourceAsStream("tesseraql/templates/tql/view/list.html").readAllBytes(),
                StandardCharsets.UTF_8);
        // The chain is the one media block that holds .tql-page-fill; the rules outside it
        // (the status line's colour, the print sheet) are not links.
        int fill = css.indexOf(".tql-page-fill{");
        assertThat(fill).as(".tql-page-fill rule in tesseraql.css").isNotNegative();
        int blockStart = css.lastIndexOf("@media", fill);
        int blockEnd = css.indexOf("\n}", fill);
        String block = css.substring(blockStart, blockEnd);
        java.util.Set<String> chained = new java.util.TreeSet<>();
        java.util.regex.Matcher rules = java.util.regex.Pattern
                .compile("\\.(tql-[a-z_-]+)\\{").matcher(block);
        while (rules.find()) {
            chained.add(rules.group(1));
        }
        java.util.Set<String> carried = new java.util.TreeSet<>();
        java.util.regex.Matcher classes = java.util.regex.Pattern
                .compile("class=\"([^\"]*)\"").matcher(page);
        while (classes.find()) {
            for (String name : classes.group(1).split("\\s+")) {
                if (name.startsWith("tql-page-fill") || (name.startsWith("tql-list-page")
                        && !name.equals("tql-list-page__status"))) {
                    carried.add(name);
                }
            }
        }
        assertThat(chained).as("the classes tesseraql.css chains")
                .containsExactly("tql-list-page", "tql-list-page__form", "tql-list-page__grid",
                        "tql-list-page__region", "tql-page-fill");
        assertThat(carried).as("the chain classes list.html carries").isEqualTo(chained);
    }

    private static String stripComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    /** Reads a file from the resolved WebJar, mirroring {@code AssetRoutes}'s lookup. */
    private static InputStream webjarResource(String path) {
        String version = new org.webjars.WebJarVersionLocator().version(WEBJAR);
        assertThat(version).as("hypermedia-components WebJar on the classpath").isNotNull();
        String resource = "META-INF/resources/webjars/" + WEBJAR + "/" + version + "/" + path;
        InputStream in = HypermediaComponentsManifestTest.class.getClassLoader()
                .getResourceAsStream(resource);
        assertThat(in).as(resource).isNotNull();
        return in;
    }

    private static List<String> names(JsonNode array) {
        List<String> names = new ArrayList<>();
        array.forEach(entry -> names.add(entry.get("name").asString()));
        return names;
    }
}
