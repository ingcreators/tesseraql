package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Drift guard against the Hypermedia Components kit (hc #manifest, exported since 0.1.9). The
 * framework bootstrap ({@code tesseraql.js}), the client message loader, and the documented htmx
 * contract ({@code docs/hypermedia-ui.md}) hard-reference kit behaviors, events, recipes, and
 * named module exports by name. If a future WebJar bump renames or drops one, pages break at
 * runtime with no compile error — this test fails the build instead. The version is resolved from
 * the classpath (same as {@link AssetsRouteBuilder}), so a clean bump keeps the guard green as
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
            "installChatScroll"); // copilot hc-chat transcript follows the stream

    /** Custom events the framework listens for / documents. */
    private static final List<String> REQUIRED_EVENTS = List.of(
            "hc:confirmed", "hc:toast", "hc:copied", "hc:datagridsort",
            "hc:themechange"); // the bootstrap persists it to the account preference

    /** Recipes the scaffolds emit and the hypermedia-ui/copilot contracts document. */
    private static final List<String> REQUIRED_RECIPES = List.of(
            "mutating-form", "field-errors", "data-region", "toast", "confirm-action",
            "chat-messages", "streaming-response"); // the copilot panel (docs/copilot.md)

    /** Named exports the framework imports from the behaviors bundle as an ES module. */
    private static final List<String> REQUIRED_BUNDLE_EXPORTS = List.of(
            "registerCodeLanguage", // tesseraql.js registers the tql-sql grammar
            "setMessages"); // ClientMessages layers the app catalog over the kit pack

    @Test
    void manifestDeclaresEveryBehaviorEventAndRecipeTheFrameworkDependsOn() throws Exception {
        JsonNode manifest = new ObjectMapper().readTree(webjarResource("dist/manifest.json"));

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

    /** Reads a file from the resolved WebJar, mirroring {@code AssetsRouteBuilder}'s lookup. */
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
        array.forEach(entry -> names.add(entry.get("name").asText()));
        return names;
    }
}
