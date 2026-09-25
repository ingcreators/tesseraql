package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code wrangler.jsonc} names no schema it cannot resolve (docs/audit-low-leads.md slice 23,
 * F94). Its {@code $schema} pointed into {@code docs-site/node_modules/wrangler/}, a package
 * nothing declares — the deploy runs {@code npx wrangler@4} in Cloudflare's build, at a Path
 * ({@code /}) with no {@code package.json} — so the editor got neither completion nor
 * validation from it, from the day the file was written. The invariant worth holding in-tree:
 * the config carries no {@code $schema}, or one that is a URL, or one whose package the
 * {@code package.json} at the deploy Path declares.
 */
class DocsSiteSchemaLedgerTest {

    private static final Path REPO = Path.of("..");

    @Test
    void theWorkerConfigNamesNoSchemaItCannotResolve() throws IOException {
        ObjectMapper jsonc = JsonMapper.builder()
                .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                .build();
        JsonNode config = jsonc.readTree(
                Files.readString(REPO.resolve("wrangler.jsonc"), StandardCharsets.UTF_8));
        JsonNode schema = config.get("$schema");
        if (schema == null) {
            return;
        }
        String reference = schema.asString();
        if (reference.startsWith("https://")) {
            return;
        }
        // A path into node_modules resolves only where the package is installed: the deploy
        // Path is the repository root, so its package.json is the one that must declare it.
        int packages = reference.indexOf("node_modules/");
        assertThat(packages).as("a non-URL $schema points into node_modules: %s", reference)
                .isNotNegative();
        String packageName = reference.substring(packages + "node_modules/".length()).split("/")[0];
        Path manifest = REPO.resolve("package.json");
        assertThat(manifest).as("the package.json at the deploy Path, which declares %s",
                packageName).isRegularFile();
        JsonNode declared = new tools.jackson.databind.json.JsonMapper()
                .readTree(manifest.toFile());
        assertThat(declared.path("devDependencies").has(packageName)
                || declared.path("dependencies").has(packageName))
                .as("%s declared in %s", packageName, manifest).isTrue();
    }
}
