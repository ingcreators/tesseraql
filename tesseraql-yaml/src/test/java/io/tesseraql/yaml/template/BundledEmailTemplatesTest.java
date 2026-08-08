package io.tesseraql.yaml.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bundled hc email fragment library (docs/notifications.md "HTML mail"): since
 * upstream brief 9 (hypermedia-components#448) the {@code tql/email/*} artifacts come
 * baked from the core package — the build unpacks {@code dist/email} out of the WebJar,
 * nothing generated is checked in, and the published {@code contract.json} is the
 * machine-readable signature contract these tests validate against (replacing the old
 * regex drift guard). Plus a render proof that the fragments resolve from an app's mail
 * template and that an app-home copy shadows the bundled library.
 */
class BundledEmailTemplatesTest {

    private static final Pattern FRAGMENT = Pattern.compile("th:fragment=\"(\\w+)");

    @Test
    void bundledLibraryMatchesThePublishedContract() throws IOException {
        com.fasterxml.jackson.databind.JsonNode contract = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(bundled("contract.json"));
        assertThat(contract.get("flavors").toString()).contains("thymeleaf");
        Set<String> contractNames = new java.util.HashSet<>();
        contract.get("fragments").forEach(f -> contractNames.add(f.get("name").asText()));

        String library = bundled("hc-email.html");
        String layout = bundled("hc-email-layout.html");
        Set<String> parsed = fragments(library);
        parsed.addAll(fragments(layout));
        assertThat(parsed).isEqualTo(contractNames);
        // The signatures Studio's palette and the wiring lint parse agree with the
        // contract's parameter lists.
        Map<String, java.util.List<String>> signatures = EmailFragments.bundled(
                EmailFragments.LIBRARY);
        contract.get("fragments").forEach(fragment -> {
            String name = fragment.get("name").asText();
            if (signatures.containsKey(name)) {
                java.util.List<String> params = new java.util.ArrayList<>();
                fragment.get("params").forEach(p -> params.add(p.asText()));
                assertThat(signatures.get(name)).as(name).isEqualTo(params);
            }
        });
        assertThat(library).contains("Axes: color=default neutral=slate flavor=thymeleaf");
        assertThat(layout).contains("Axes: color=default neutral=slate flavor=thymeleaf");
    }

    @Test
    void mailTemplateComposesTheBundledFragments(@TempDir Path appHome) throws IOException {
        Files.createDirectories(appHome.resolve("templates/mail"));
        Files.writeString(appHome.resolve("templates/mail/notice.html"),
                """
                        <div th:replace="~{tql/email/hc-email-layout :: hcLayout('Notice',
                            |Hello ${payload.name}|, ~{:: content})}">
                          <div th:fragment="content">
                            <div th:replace="~{tql/email/hc-email :: hcHeading('Notice')}"></div>
                            <div th:replace="~{tql/email/hc-email :: hcText(|Hello ${payload.name}.|)}"></div>
                            <div th:replace="~{tql/email/hc-email :: hcButton(${payload.url}, 'Open')}"></div>
                          </div>
                        </div>
                        """);

        String html = Templates.render(appHome, "templates/mail/notice.html", Map.of(
                "payload", Map.of("name", "Suzuki", "url", "https://example.com/t/1")));

        // The layout shell wraps the content fragment once, styling stays inline, and the
        // wrapper's inline fragment definition is not emitted a second time.
        assertThat(html).contains("<title>Notice</title>");
        assertThat(html).containsOnlyOnce("Hello Suzuki.");
        assertThat(html).contains("href=\"https://example.com/t/1\"");
        assertThat(html).contains("background-color:#2563eb");
        assertThat(html).doesNotContain("th:replace");
    }

    @Test
    void appTemplatesShadowTheBundledLibrary(@TempDir Path appHome) throws IOException {
        Files.createDirectories(appHome.resolve("templates/tql/email"));
        Files.createDirectories(appHome.resolve("templates/mail"));
        Files.writeString(appHome.resolve("templates/tql/email/hc-email.html"), """
                <p th:fragment="hcText(text)" th:text="${text}" style="color:#123456;"></p>
                """);
        Files.writeString(appHome.resolve("templates/mail/notice.html"), """
                <div th:replace="~{tql/email/hc-email :: hcText('themed')}"></div>
                """);

        String html = Templates.render(appHome, "templates/mail/notice.html", Map.of());

        assertThat(html).contains("color:#123456").contains("themed");
    }

    private static String bundled(String name) {
        String resource = "tesseraql/templates/tql/email/" + name;
        try (InputStream in = BundledEmailTemplatesTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private static Set<String> fragments(String template) {
        Matcher matcher = FRAGMENT.matcher(template);
        Set<String> names = new java.util.HashSet<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
