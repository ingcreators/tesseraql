package io.tesseraql.core.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The two allow-lists, pinned over all of ASCII on both sides, and the encoder pinned against
 * the JDK's UTF-8 over every code point — a sampled list would let a one-character edit ship
 * (docs/download-name-and-bytes.md).
 */
class PercentEncodingTest {

    private static final String ATTR_CHAR = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#$&+-.^_`|~";

    private static final String URI_CHAR = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#[]@!$&'()*+,;=";

    /** RFC 8187 attr-char is 74 characters; every other ASCII code point is a triplet. */
    @Test
    void theAttrCharListIsExactlyRfc8187OverAllOfAscii() {
        assertThat(ATTR_CHAR).hasSize(74);
        assertThat(PercentEncoding.extValue(ATTR_CHAR)).isEqualTo(ATTR_CHAR);
        for (int c = 0; c < 0x80; c++) {
            String in = String.valueOf((char) c);
            assertThat(PercentEncoding.extValue(in)).as("U+%04X", c)
                    .isEqualTo(ATTR_CHAR.indexOf(c) >= 0 ? in : "%%%02X".formatted(c));
        }
    }

    /**
     * The URI list is what RFC 3986 lets a reference spell: unreserved, gen-delims, sub-delims
     * and a {@code %} that starts a triplet. The eight visible characters outside it
     * ({@code " < > \ ^ ` { | }}), space, the controls and DEL are encoded.
     */
    @Test
    void theUriLiteralListIsExactlyWhatRfc3986AdmitsOverAllOfAscii() {
        assertThat(PercentEncoding.uriLiteral(URI_CHAR)).isEqualTo(URI_CHAR);
        for (int c = 0; c < 0x80; c++) {
            String in = String.valueOf((char) c);
            assertThat(PercentEncoding.uriLiteral(in)).as("U+%04X", c)
                    .isEqualTo(URI_CHAR.indexOf(c) >= 0 ? in : "%%%02X".formatted(c));
        }
        assertThat(PercentEncoding.uriLiteral("http://h:8080/a;b,c@d+e~f'g!h$i(j)k*l[m]?x=1&y#z"))
                .isEqualTo("http://h:8080/a;b,c@d+e~f'g!h$i(j)k*l[m]?x=1&y#z");
        assertThat(PercentEncoding.uriLiteral("/a\"b<c>d\\e^f`g{h|i}"))
                .isEqualTo("/a%22b%3Cc%3Ed%5Ce%5Ef%60g%7Bh%7Ci%7D");
    }

    @Test
    void aUriLiteralIsIdempotentOnAnAuthoredReference() {
        String authored = "/caf%C3%A9/J-1001?x=1&y=%20#top";
        assertThat(PercentEncoding.uriLiteral(authored)).isEqualTo(authored);
        String once = PercentEncoding.uriLiteral("/受注/A 1\"{}%zz%C3\u0001\u007F");
        assertThat(once).isEqualTo("/%E5%8F%97%E6%B3%A8/A%201%22%7B%7D%25zz%C3%01%7F");
        assertThat(PercentEncoding.uriLiteral(once)).isEqualTo(once);
    }

    /** A {@code %} that starts no {@code pct-encoded} triplet is data, not an escape. */
    @Test
    void aPercentThatStartsNoTripletIsEncoded() {
        assertThat(PercentEncoding.uriLiteral("/100%")).isEqualTo("/100%25");
        assertThat(PercentEncoding.uriLiteral("/a%zz")).isEqualTo("/a%25zz");
        assertThat(PercentEncoding.uriLiteral("/a%4")).isEqualTo("/a%254");
        assertThat(PercentEncoding.uriLiteral("%")).isEqualTo("%25");
        assertThat(PercentEncoding.uriLiteral("%41%4a%4F")).isEqualTo("%41%4a%4F");
        // HEXDIG is ASCII: a fullwidth digit after % does not make a triplet.
        assertThat(PercentEncoding.uriLiteral("/a%１２")).isEqualTo("/a%25%EF%BC%91%EF%BC%92");
    }

    @Test
    void anAstralCharacterIsFourOctetsAndALoneSurrogateIsTheReplacementCharacter() {
        assertThat(PercentEncoding.extValue("a😀b")).isEqualTo("a%F0%9F%98%80b");
        assertThat(PercentEncoding.uriLiteral("/😀")).isEqualTo("/%F0%9F%98%80");
        assertThat(PercentEncoding.extValue("a\uD83Db")).isEqualTo("a%EF%BF%BDb");
        assertThat(PercentEncoding.extValue("a\uDE00b")).isEqualTo("a%EF%BF%BDb");
        assertThat(PercentEncoding.uriLiteral("/\uDC00")).isEqualTo("/%EF%BF%BD");
        assertThat(PercentEncoding.extValue("\uDE00\uD83D")).isEqualTo("%EF%BF%BD%EF%BF%BD");
        // An astral code point whose value looks like a surrogate when cast is a character.
        assertThat(PercentEncoding.extValue("a\uD836\uDC00b")).isEqualTo("a%F0%9D%A0%80b");
    }

    /** The hand-written UTF-8 agrees with the JDK's on every code point, upper-case hex. */
    @Test
    void theEncoderAgreesWithTheJdkOnEveryCodePoint() {
        for (int cp = 0x80; cp <= Character.MAX_CODE_POINT; cp++) {
            if (cp >= 0xD800 && cp <= 0xDFFF) {
                continue;
            }
            String text = Character.toString(cp);
            StringBuilder expected = new StringBuilder();
            for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
                expected.append("%%%02X".formatted(b & 0xFF));
            }
            assertThat(PercentEncoding.extValue(text)).as("U+%04X", cp)
                    .isEqualTo(expected.toString());
        }
        // The length boundaries by name: U+007F U+0080 U+07FF U+0800 U+FFFF U+10000 U+10FFFF.
        assertThat(
                PercentEncoding.extValue("\u007F\u0080\u07FF\u0800\uFFFF\uD800\uDC00\uDBFF\uDFFF"))
                .isEqualTo("%7F%C2%80%DF%BF%E0%A0%80%EF%BF%BF%F0%90%80%80%F4%8F%BF%BF");
        assertThat(PercentEncoding.extValue("รายงาน")).isEqualTo(
                "%E0%B8%A3%E0%B8%B2%E0%B8%A2%E0%B8%87%E0%B8%B2%E0%B8%99");
    }

    @Test
    void nullIsACallerError() {
        assertThatNullPointerException().isThrownBy(() -> PercentEncoding.extValue(null));
        assertThatNullPointerException().isThrownBy(() -> PercentEncoding.uriLiteral(null));
    }
}
