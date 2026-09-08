package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Keys the framework reads through a helper reach the configuration index.
 *
 * <p>The page's header promises every key it can see, and for a whole class of reads it could see
 * nothing: a key handed to a same-file wrapper — {@code flag("tesseraql.scim.enabled", false)},
 * {@code readSql(config, …)}, {@code duration(config, …, …)} — never appeared beside a config
 * accessor, so the scan missed it. That hid the entire SCIM enable-and-contract surface and the
 * JWT, JWKS and mTLS clock tuning from an operator who followed a troubleshooting page here.
 *
 * <p>The sample below is deliberately spread across all four modules and all seven helper shapes,
 * rather than being the full 26: the point is that each distinct wrapper is reached, and a list of
 * every key would be a second roster to maintain by hand.
 *
 * <p><b>What this cannot see.</b> A key composed from a prefix at request time has no literal to
 * find and is out of scope by construction — the page now says so. This asserts on the rendered
 * page rather than on the scanner's internals, because the page is what an operator reads.
 */
class ConfigKeyLedgerTest {

    private static final Path REPO = Path.of("..");

    /** One key per helper shape, from every module that has one. */
    private static final List<String> READ_THROUGH_A_HELPER = List.of(
            "tesseraql.scim.enabled", // ScimRuntimeExtension.flag
            "tesseraql.scim.users.list", // ScimRuntimeExtension.readSql
            "tesseraql.scim.groups.listMembers", // ScimRuntimeExtension.readSqlOptional
            "tesseraql.security.jwt.clockSkew", // SecurityConfigFactory.duration
            "tesseraql.security.jwt.jwks.cacheTtl", // the same, on the JWKS cache
            "tesseraql.files.timezone", // RouteCompiler.formatDeclaration
            "tesseraql.oidc.enabled", // LoginMethods.flag
            "tesseraql.saml.enabled", // ManifestCoverage.flag
            "tesseraql.oidc.clientId"); // OidcSamlRules.rawString

    @Test
    void everyKeyReadThroughAHelperIsOnThePage() throws IOException {
        String page = ReferenceGenerator.config(REPO);

        // Sensitivity: a key read the ordinary way must be present, or a scan that produced
        // nothing at all would fail below for the wrong reason and read as this defect.
        assertThat(page).as("the index renders at all")
                .contains("| `tesseraql.studio.enabled` |");

        for (String key : READ_THROUGH_A_HELPER) {
            assertThat(page)
                    .as("a key read through a same-file helper reaches the index: %s", key)
                    .contains("| `" + key + "` |");
        }
    }

    /**
     * A helper that writes a key, or names one only in a refusal message, is not a reader.
     *
     * <p>These are the two false positives the second scan pattern would otherwise introduce, and
     * they are asserted because provenance is what this page offers instead of prose: a row citing
     * {@code StudioProviders} as reading a SAML key would send an operator to the Studio wizard's
     * overlay writer to find out what the framework does with it.
     */
    @Test
    void aWriterAndAMessageAreNotReads() throws IOException {
        String page = ReferenceGenerator.config(REPO);

        assertThat(row(page, "tesseraql.saml.idp.metadata"))
                .as("StudioProviders writes this key into the Studio wizard's overlay; it is not"
                        + " a reader of it")
                .doesNotContain("StudioProviders.java");

        assertThat(row(page, "tesseraql.oidc.clientId"))
                .as("OidcRuntimeExtension names this key only inside a refusal message; the read"
                        + " is in OidcConfig")
                .doesNotContain("OidcRuntimeExtension.java");
    }

    /** The one rendered row for {@code key}, or a failure saying it is absent. */
    private static String row(String page, String key) {
        String marker = "| `" + key + "` |";
        int start = page.indexOf(marker);
        assertThat(start).as("the index has a row for %s", key).isNotNegative();
        int end = page.indexOf('\n', start);
        return page.substring(start, end < 0 ? page.length() : end);
    }
}
