package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShareLinksTest {

    private static Map<String, String> query(String url) {
        Map<String, String> params = new HashMap<>();
        String q = url.substring(url.indexOf('?') + 1);
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    @Test
    void mintsAndVerifiesASignedExpiringLink() {
        ShareLinks links = ShareLinks.of("a-share-secret", 3600);
        assertThat(links.enabled()).isTrue();

        String url = links.mintRoute("users.search");
        assertThat(url).startsWith("/_tesseraql/docs/share/route?id=users.search&exp=");
        Map<String, String> q = query(url);
        // A freshly minted token verifies.
        assertThat(links.verifyRoute(q.get("id"), q.get("exp"), q.get("sig"))).isTrue();
    }

    @Test
    void mintsAndVerifiesTableAndCoverageLinks() {
        ShareLinks links = ShareLinks.of("a-share-secret", 3600);

        String table = links.mintTable("main", "orders");
        assertThat(table).startsWith("/_tesseraql/docs/share/table?ds=main&name=orders&exp=");
        Map<String, String> t = query(table);
        assertThat(links.verifyTable("main", "orders", t.get("exp"), t.get("sig"))).isTrue();
        // Retargeting to another table (or datasource) breaks the signature.
        assertThat(links.verifyTable("main", "customers", t.get("exp"), t.get("sig"))).isFalse();
        assertThat(links.verifyTable("other", "orders", t.get("exp"), t.get("sig"))).isFalse();

        String coverage = links.mintCoverage();
        assertThat(coverage).startsWith("/_tesseraql/docs/share/coverage?exp=");
        Map<String, String> c = query(coverage);
        assertThat(links.verifyCoverage(c.get("exp"), c.get("sig"))).isTrue();
        assertThat(links.verifyCoverage(c.get("exp"), "forged")).isFalse();
    }

    @Test
    void aTokenForOneKindDoesNotVerifyAsAnother() {
        ShareLinks links = ShareLinks.of("a-share-secret", 3600);
        // A route token's id "coverage" must not be replayable as a coverage link, and vice versa:
        // the per-kind label is part of the signed subject.
        Map<String, String> route = query(links.mintRoute("coverage"));
        assertThat(links.verifyCoverage(route.get("exp"), route.get("sig"))).isFalse();
        Map<String, String> table = query(links.mintTable("main", "orders"));
        assertThat(links.verifyRoute("main", table.get("exp"), table.get("sig"))).isFalse();
    }

    @Test
    void rejectsTamperedIdExpirySignatureAndMissingParts() {
        ShareLinks links = ShareLinks.of("a-share-secret", 3600);
        Map<String, String> q = query(links.mintRoute("users.search"));

        // Retargeting the link to another route breaks the signature.
        assertThat(links.verifyRoute("users.delete", q.get("exp"), q.get("sig"))).isFalse();
        // Extending the expiry breaks the signature (exp is signed).
        assertThat(links.verifyRoute(q.get("id"),
                String.valueOf(Long.parseLong(q.get("exp")) + 86400), q.get("sig"))).isFalse();
        // A forged/garbage signature is rejected.
        assertThat(links.verifyRoute(q.get("id"), q.get("exp"), "not-a-signature")).isFalse();
        // Missing or non-numeric parts are rejected, not thrown.
        assertThat(links.verifyRoute(q.get("id"), null, q.get("sig"))).isFalse();
        assertThat(links.verifyRoute(q.get("id"), "abc", q.get("sig"))).isFalse();
        assertThat(links.verifyRoute(null, q.get("exp"), q.get("sig"))).isFalse();
    }

    @Test
    void rejectsAnExpiredButCorrectlySignedToken() {
        // A negative ttl mints a token whose (signed) expiry is already in the past.
        ShareLinks links = ShareLinks.of("a-share-secret", -10);
        Map<String, String> q = query(links.mintRoute("users.search"));

        // The signature is valid, but the expiry has passed -> rejected.
        assertThat(links.verifyRoute(q.get("id"), q.get("exp"), q.get("sig"))).isFalse();
    }

    @Test
    void aDifferentSecretDoesNotVerify() {
        ShareLinks signer = ShareLinks.of("secret-one", 3600);
        ShareLinks other = ShareLinks.of("secret-two", 3600);
        Map<String, String> q = query(signer.mintRoute("users.search"));

        assertThat(other.verifyRoute(q.get("id"), q.get("exp"), q.get("sig"))).isFalse();
    }

    @Test
    void disabledWhenNoSecretIsConfigured() {
        ShareLinks disabled = ShareLinks.of(null, 3600);

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.mintRoute("users.search")).isNull();
        assertThat(disabled.verifyRoute("users.search", "9999999999", "sig")).isFalse();
    }
}
