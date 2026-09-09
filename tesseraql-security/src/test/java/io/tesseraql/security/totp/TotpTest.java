package io.tesseraql.security.totp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * RFC 6238 conformance (roadmap Phase 50 slice 3): the appendix's SHA-1 test vectors
 * (their last six digits — the RFC prints eight), Base32 round-trips, and the
 * matched-step contract the replay guard builds on.
 */
class TotpTest {

    /** The RFC 6238 appendix secret: ASCII "12345678901234567890". */
    private static final String RFC_SECRET = Base32.encode(
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matchesTheRfc6238Sha1Vectors() {
        // (unix time, 8-digit RFC code) -> we assert the 6-digit truncation at T/30.
        assertThat(Totp.codeAt(RFC_SECRET, 59L / 30)).isEqualTo("287082");
        assertThat(Totp.codeAt(RFC_SECRET, 1111111109L / 30)).isEqualTo("081804");
        assertThat(Totp.codeAt(RFC_SECRET, 1234567890L / 30)).isEqualTo("005924");
        assertThat(Totp.codeAt(RFC_SECRET, 20000000000L / 30)).isEqualTo("353130");
    }

    @Test
    void base32RoundTripsAndDecodesAuthenticatorFriendlyForms() {
        byte[] data = new byte[]{0, 1, 2, 3, (byte) 0xff, 42, 7};
        assertThat(Base32.decode(Base32.encode(data))).isEqualTo(data);
        // Lowercase, spaces, and padding all decode - what users paste back.
        assertThat(Base32.decode("mzxw 6ytb oi==")).isEqualTo(
                "foobar".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void matchedStepFindsNeighboursAndRefusesGarbage() {
        String secret = Totp.generateSecret();
        long now = Totp.currentStep();
        assertThat(Totp.matchedStep(secret, Totp.codeAt(secret, now))).isEqualTo(now);
        assertThat(Totp.matchedStep(secret, Totp.codeAt(secret, now + 1))).isEqualTo(now + 1);
        assertThat(Totp.matchedStep(secret, Totp.codeAt(secret, now - 1))).isEqualTo(now - 1);
        assertThat(Totp.matchedStep(secret, Totp.codeAt(secret, now + 5))).isEqualTo(-1);
        assertThat(Totp.matchedStep(secret, "12345")).isEqualTo(-1);
        assertThat(Totp.matchedStep(secret, "not-a-code")).isEqualTo(-1);
        assertThat(Totp.matchedStep(secret, null)).isEqualTo(-1);
    }

    /**
     * A TOTP code is an identity, not a rendering: the same secret and step must produce the
     * same six characters on every JVM. Under a default locale whose numbering system is not
     * Latin — Arabic-Indic (ar-EG), Bengali (bn-BD), Devanagari — a locale-sensitive format
     * would render {@code ٤٧٠٧٦٢}, which {@link Totp#matchedStep} then refuses because Java's
     * {@code \d} is ASCII-only. The server would reject the code it had just generated, and
     * every user with MFA enabled would be locked out in both directions.
     *
     * <p>There is no parallel test execution in this repository, so restoring the default in a
     * finally block is enough; a lock would guard against a scheduler that does not exist.
     */
    @Test
    void aCodeIsTheSameSixCharactersUnderALocaleWithItsOwnDigits() {
        Locale original = Locale.getDefault();
        try {
            for (Locale locale : new Locale[]{
                    Locale.of("ar", "EG"), Locale.of("bn", "BD"),
                    Locale.forLanguageTag("hi-IN-u-nu-deva")}) {
                Locale.setDefault(locale);

                // The RFC vector is a fixed string, whatever the JVM thinks digits look like.
                assertThat(Totp.codeAt(RFC_SECRET, 59L / 30))
                        .as("RFC 6238 vector under %s", locale)
                        .isEqualTo("287082");

                // And the round trip the login flow actually performs still closes.
                String secret = Totp.generateSecret();
                long now = Totp.currentStep();
                String generated = Totp.codeAt(secret, now);
                assertThat(generated).as("code under %s is ASCII", locale).matches("\\d{6}");
                assertThat(Totp.matchedStep(secret, generated))
                        .as("the server accepts the code it generated under %s", locale)
                        .isEqualTo(now);
            }
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void theOtpauthUriCarriesTheStandardFields() {
        String uri = Totp.otpauthUri("My App", "alice@example.com", "SECRET234");
        assertThat(uri).startsWith("otpauth://totp/My%20App:alice%40example.com?")
                .contains("secret=SECRET234")
                .contains("issuer=My%20App")
                .contains("digits=6").contains("period=30");
    }
}
