package io.tesseraql.yaml.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Keying a per-row reference's URL (docs/lookups.md, decision 21). */
class KeyedUrlsTest {

    @Test
    void everyPlaceholderTakesItsKeyColumn() {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("buyer", "B1");
        key.put("supplier", "S1");
        assertThat(KeyedUrls.fill("https://crm/partners/{key.buyer}/suppliers/{key.supplier}", key))
                .isEqualTo("https://crm/partners/B1/suppliers/S1");
    }

    @Test
    void aSlashInAKeyCannotReachAnotherResource() {
        // URLEncoder would pass '/' through, which is the whole point of encoding here.
        assertThat(KeyedUrls.fill("https://crm/partners/{key.code}", Map.of("code", "../admin")))
                .isEqualTo("https://crm/partners/..%2Fadmin");
    }

    @Test
    void aSpaceIsPercentEncodedRatherThanTurnedIntoAPlus() {
        assertThat(KeyedUrls.encode("a b")).isEqualTo("a%20b");
    }

    @Test
    void multiByteKeysEncodeAsUtf8() {
        assertThat(KeyedUrls.encode("受注")).isEqualTo("%E5%8F%97%E6%B3%A8");
    }

    @Test
    void unreservedCharactersSurviveUntouched() {
        assertThat(KeyedUrls.encode("A-9._~")).isEqualTo("A-9._~");
    }

    @Test
    void aUrlWithoutPlaceholdersIsNotKeyed() {
        assertThat(KeyedUrls.isKeyed("https://crm/partners/search")).isFalse();
        assertThat(KeyedUrls.isKeyed("https://crm/partners/{key.code}")).isTrue();
    }
}
