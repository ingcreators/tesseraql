package io.tesseraql.core.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HmacSignaturesTest {

    @Test
    void signsAndVerifiesTimestampAndBody() {
        byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        String signature = HmacSignatures.sign("secret", "1750000000", body);

        assertThat(signature).startsWith("sha256=").hasSize("sha256=".length() + 64);
        assertThat(HmacSignatures.verify("secret", "1750000000", body, signature)).isTrue();
    }

    @Test
    void rejectsTamperedBodyTimestampOrKey() {
        byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        String signature = HmacSignatures.sign("secret", "1750000000", body);

        assertThat(HmacSignatures.verify("secret", "1750000001", body, signature)).isFalse();
        assertThat(HmacSignatures.verify("other", "1750000000", body, signature)).isFalse();
        assertThat(HmacSignatures.verify("secret", "1750000000",
                "{}".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
        assertThat(HmacSignatures.verify("secret", "1750000000", body, null)).isFalse();
    }

    @Test
    void matchesAReferenceVector() {
        // Computable with any HMAC-SHA256 tool over "ts.body", so receivers can be implemented
        // against the documented scheme without running TesseraQL.
        String signature = HmacSignatures.sign("key", "0",
                "body".getBytes(StandardCharsets.UTF_8));
        assertThat(signature).isEqualTo(
                "sha256=e0af04d5c83b24373ff89f540d0c8fd9a4e097e2b3ee8318ab5541047697626d");
    }
}
