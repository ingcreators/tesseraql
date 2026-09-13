package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.Beans;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The request-time judge and the fallback chain of a file recipe's {@code locale:} and
 * {@code timezone:} (docs/export-declarations.md decisions 27-32): a value the request supplied
 * is held to the literal predicate before any SQL runs and refused in the input binder's shape;
 * a literal, the configured default and the negotiated locale are never judged here; and each
 * key walks literal, source, configuration, platform on its own.
 *
 * <p>No database and no runtime: an {@link Exchange} carries the bound request context, the
 * principal or the negotiated locale, exactly as the binders find it.
 */
class RequestFormatsTest {

    private static final FormatDeclaration TZ_QUERY = FormatDeclaration.of("timezone", "query.tz",
            "America/Los_Angeles");
    private static final FormatDeclaration LOC_QUERY = FormatDeclaration.of("locale", "query.loc",
            "de");
    private static final FormatDeclaration TZ_CLAIM = FormatDeclaration.of("timezone",
            "principal.claim.zoneinfo", "America/Los_Angeles");
    private static final FormatDeclaration LOC_CLAIM = FormatDeclaration.of("locale",
            "principal.claim.locale", "de");

    /** A route literal was judged at boot; the binder answers it as it is, valid or not. */
    @Test
    void aRouteLiteralIsNeverJudgedHere() {
        assertThat(RequestFormats.timezone(request(Map.of()),
                FormatDeclaration.of("timezone", "Asia/Kolkata", "America/Los_Angeles")))
                .isEqualTo("Asia/Kolkata");
        // Red when every provenance is judged: a literal boot let through is not the caller's.
        assertThatCode(() -> RequestFormats.timezone(request(Map.of()),
                FormatDeclaration.of("timezone", "Not/AZone", "America/Los_Angeles")))
                .doesNotThrowAnyException();
    }

    /** Red when the configured literal is consulted before the source. */
    @Test
    void aResolvedSourceWinsOverTheConfiguredLiteral() {
        assertThat(RequestFormats.timezone(request(Map.of("tz", "Asia/Tokyo")), TZ_QUERY))
                .isEqualTo("Asia/Tokyo");
    }

    /** Before the chain an unresolved source answered null and skipped the configuration. */
    @Test
    void anAbsentSourceFallsToTheConfiguredLiteral() {
        assertThat(RequestFormats.timezone(request(Map.of()), TZ_QUERY))
                .isEqualTo("America/Los_Angeles");
    }

    /** {@code ?tz=} is unset, not a value to judge: red when a blank is judged or passed on. */
    @Test
    void aBlankSourceIsUnsetAndFallsToTheConfiguredLiteral() {
        assertThat(RequestFormats.timezone(request(Map.of("tz", "  ")), TZ_QUERY))
                .isEqualTo("America/Los_Angeles");
    }

    /** Null is the platform default; red when an unset key answers an empty string. */
    @Test
    void anAbsentSourceWithNoConfiguredLiteralIsThePlatformDefault() {
        assertThat(RequestFormats.timezone(request(Map.of()),
                FormatDeclaration.of("timezone", "query.tz", null))).isNull();
    }

    /**
     * The values AND the provenance per key: a coupling that shares the first key's provenance
     * answers the right values on a bare exchange and is only seen through the provenance.
     */
    @Test
    void theTwoKeysWalkTheChainIndependently() {
        Exchange exchange = request(Map.of("loc", "ja-JP"));

        FormatSources.Resolved locale = FormatSources.resolve(exchange, LOC_QUERY);
        FormatSources.Resolved timezone = FormatSources.resolve(exchange, TZ_QUERY);

        assertThat(RequestFormats.locale(exchange, LOC_QUERY)).isEqualTo("ja-JP");
        assertThat(RequestFormats.timezone(exchange, TZ_QUERY)).isEqualTo("America/Los_Angeles");
        assertThat(locale.provenance()).isEqualTo(FormatSources.Provenance.SOURCE);
        assertThat(timezone.provenance()).isEqualTo(FormatSources.Provenance.CONFIG);
    }

    /**
     * Map equality on the field entry: no {@code value} on the wire, the code is the key, the
     * message key is the input's, the source is the declaration.
     */
    @Test
    void aBadRequestSourcedZoneIsRefusedInTheInputBinderShape() {
        TqlException refusal = refusal(() -> RequestFormats.timezone(
                request(Map.of("tz", "Tokyo")), TZ_QUERY));

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("field", "tz");
        expected.put("code", "timezone");
        expected.put("message", "tql.input.timezone");
        expected.put("source", "query.tz");
        assertThat(refusal.code().toString()).isEqualTo("TQL-FIELD-2001");
        assertThat(firstField(refusal)).isEqualTo(expected);
    }

    /**
     * The strict locale rule, from a query source: an underscore, a name, an unknown language,
     * {@code und} and a private-use tag are refused; an extension, the JDK-encoded Nynorsk
     * spelling and a legacy language code pass.
     */
    @Test
    void aBadRequestSourcedLocaleIsRefusedInTheInputBinderShape() {
        for (String bad : List.of("ja_JP", "japanese", "xx-YY", "und", "x-private", "no-NO-NY")) {
            TqlException refusal = refusal(() -> RequestFormats.locale(
                    request(Map.of("loc", bad)), LOC_QUERY));
            assertThat(firstField(refusal).get("code")).as(bad).isEqualTo("locale");
        }
        for (String good : List.of("ja-JP-u-nu-fullwide", "nn-NO", "no-NO-x-lvariant-NY", "iw")) {
            assertThat(RequestFormats.locale(request(Map.of("loc", good)), LOC_QUERY))
                    .as(good).isEqualTo(good);
        }
    }

    /** {@code body.report.loc} names {@code report.loc}, not the last segment alone. */
    @Test
    void aNestedBodySourceNamesTheFieldBelowItsPrefix() {
        TqlException refusal = refusal(() -> RequestFormats.locale(request(Map.of()),
                FormatDeclaration.of("locale", "body.report.loc", null)));

        assertThat(firstField(refusal).get("field")).isEqualTo("report.loc");
    }

    /**
     * A claim is nobody's form field: the field is the whole expression, the message key is the
     * claim's own, and the log line says which claim.
     */
    @Test
    void aBadIdpClaimIsRefusedNamingTheClaimUnderTheClaimMessageKey() {
        TqlException refusal = refusal(() -> RequestFormats.timezone(
                principal(Map.of("zoneinfo", "Asia/Tokio")), TZ_CLAIM));

        Map<String, Object> field = firstField(refusal);
        assertThat(field.get("field")).isEqualTo("principal.claim.zoneinfo");
        assertThat(field.get("source")).isEqualTo("principal.claim.zoneinfo");
        assertThat(field.get("code")).isEqualTo("timezone");
        assertThat(field.get("message")).isEqualTo("tql.input.claim.timezone");
        assertThat(refusal.getMessage()).contains("IdP claim 'zoneinfo'");
    }

    /** A JSON number, array or object is refused as such, never stringified and then judged. */
    @Test
    void aNonTextClaimOrInputIsRefusedAsNotText() {
        TqlException number = refusal(() -> RequestFormats.timezone(
                principal(Map.of("zoneinfo", 9)), TZ_CLAIM));
        TqlException array = refusal(() -> RequestFormats.timezone(
                principal(Map.of("zoneinfo", List.of("Asia/Tokyo"))), TZ_CLAIM));
        TqlException object = refusal(() -> RequestFormats.timezone(
                principal(Map.of("zoneinfo", Map.of("id", "Asia/Tokyo"))), TZ_CLAIM));
        TqlException body = refusal(() -> RequestFormats.timezone(request(Map.of()),
                FormatDeclaration.of("timezone", "body.tz", null)));

        assertThat(number.getMessage()).contains("is not a text value but a number");
        assertThat(array.getMessage()).contains("is not a text value but an array");
        assertThat(object.getMessage()).contains("is not a text value but an object");
        assertThat(body.getMessage()).contains("Input 'tz' is not a text value but a number");
        assertThat(firstField(body))
                .containsEntry("field", "tz")
                .containsEntry("message", "tql.input.timezone");
    }

    @Test
    void anAbsentIdpClaimFallsToTheConfiguredLiteral() {
        assertThat(RequestFormats.timezone(principal(Map.of()), TZ_CLAIM))
                .isEqualTo("America/Los_Angeles");
    }

    /**
     * The exception message is the DEBUG line: the value in it is cut at forty code points with
     * an ellipsis, and a newline, a NEL or a line separator cannot forge a second line.
     */
    @Test
    void theLogMessageBoundsAndStripsTheValue() {
        String big = "A".repeat(300) + "\n";
        TqlException refusal = refusal(() -> RequestFormats.timezone(
                request(Map.of("tz", big)), TZ_QUERY));
        String message = refusal.getMessage();
        int open = message.indexOf('\'', message.indexOf("Input 'tz'") + 10);
        int close = message.indexOf('\'', open + 1);

        assertThat(message).contains("...").doesNotContain("\n");
        assertThat(close - open - 1).as("the quoted value").isBetween(0, 43);

        TqlException separators = refusal(() -> RequestFormats.timezone(
                request(Map.of("tz", "Asia/Tokyo\u0085X\u2028Y")), TZ_QUERY));
        assertThat(separators.getMessage()).doesNotContain("\u0085").doesNotContain("\u2028");
    }

    /**
     * The configured literal is never read as a source: a key that happens to spell
     * {@code query.tz} answers {@code query.tz} with provenance CONFIG, and the caller's
     * {@code tz} is not blamed for it.
     */
    @Test
    void theConfiguredLiteralIsNeverResolvedAsASource() {
        Exchange exchange = request(Map.of("tz", "Asia/Tokyo"));
        FormatDeclaration undeclared = FormatDeclaration.of("timezone", null, "query.tz");
        FormatDeclaration blank = FormatDeclaration.of("timezone", "  ", "query.tz");

        FormatSources.Resolved resolved = FormatSources.resolve(exchange, undeclared);

        assertThat(resolved.value()).isEqualTo("query.tz");
        assertThat(resolved.provenance()).isEqualTo(FormatSources.Provenance.CONFIG);
        assertThat(RequestFormats.timezone(exchange, undeclared)).isEqualTo("query.tz");
        assertThat(RequestFormats.timezone(exchange, blank)).isEqualTo("query.tz");
    }

    /**
     * {@code request.locale} is the framework's answer, not the caller's: {@code und} (what an
     * i18n default that folds produces) passes through as NEGOTIATED, and an absent property
     * falls to the configuration.
     */
    @Test
    void theNegotiatedLocaleIsNeverJudgedHere() {
        FormatDeclaration negotiated = FormatDeclaration.of("locale", "request.locale", "de");

        FormatSources.Resolved resolved = FormatSources.resolve(negotiatedLocale("und"),
                negotiated);

        assertThat(RequestFormats.locale(negotiatedLocale("und"), negotiated)).isEqualTo("und");
        assertThat(resolved.provenance()).isEqualTo(FormatSources.Provenance.NEGOTIATED);
        assertThat(RequestFormats.locale(negotiatedLocale("ja-JP"), negotiated))
                .isEqualTo("ja-JP");
        assertThat(RequestFormats.locale(negotiatedLocale(null), negotiated)).isEqualTo("de");
    }

    /**
     * OpenID Connect Core 1.0 sanctions {@code en_US} as a compatibility spelling of a locale
     * claim (decision 30): a principal-sourced locale is read in its dash form when and only
     * when the dash form passes the strict rule; a query source keeps the strict rule.
     */
    @Test
    void anUnderscoreClaimLocaleIsReadInItsDashForm() {
        assertThat(RequestFormats.locale(principal(Map.of("locale", "en_US")), LOC_CLAIM))
                .isEqualTo("en-US");
        assertThat(RequestFormats.locale(principal(Map.of("locale", "ja_JP")), LOC_CLAIM))
                .isEqualTo("ja-JP");
        assertThat(RequestFormats.locale(principal(Map.of("locale", "de_DE")), LOC_CLAIM))
                .isEqualTo("de-DE");
        assertThat(RequestFormats.locale(principal(Map.of("locale", "de-CH")), LOC_CLAIM))
                .isEqualTo("de-CH");

        TqlException posix = refusal(() -> RequestFormats.locale(
                principal(Map.of("locale", "en_US.UTF-8")), LOC_CLAIM));
        assertThat(firstField(posix).get("code")).isEqualTo("locale");
        assertThat(posix.getMessage()).as("the original spelling is quoted")
                .contains("'en_US.UTF-8'");
        assertThat(firstField(refusal(() -> RequestFormats.locale(
                principal(Map.of("locale", "japanese")), LOC_CLAIM))).get("code"))
                .isEqualTo("locale");
        // The caller can retype an input; the operator cannot retype an identity provider.
        assertThat(firstField(refusal(() -> RequestFormats.locale(
                request(Map.of("loc", "ja_JP")), LOC_QUERY))).get("code")).isEqualTo("locale");
    }

    /**
     * The predicate is {@code ZoneId.of} on the bytes as sent: a padded or case-folded id is
     * refused, not trimmed into a value the codec would then refuse on its own.
     */
    @Test
    void aWhitespacePaddedOrCaseFoldedZoneIsRefusedNotTrimmed() {
        for (String bad : List.of(" Asia/Tokyo", "Asia/Tokyo ", "asia/tokyo")) {
            TqlException refusal = refusal(() -> RequestFormats.timezone(
                    request(Map.of("tz", bad)), TZ_QUERY));
            assertThat(firstField(refusal).get("code")).as(bad).isEqualTo("timezone");
        }
        assertThat(RequestFormats.timezone(request(Map.of("tz", "UTC+9")), TZ_QUERY))
                .isEqualTo("UTC+9");
        assertThat(RequestFormats.timezone(request(Map.of("tz", "+09:00")), TZ_QUERY))
                .isEqualTo("+09:00");
    }

    /** The short ids the codec refuses are refused here too, not mapped through SHORT_IDS. */
    @Test
    void aShortIdZoneIsRefused() {
        for (String bad : List.of("PST", "EST", "JST")) {
            TqlException refusal = refusal(() -> RequestFormats.timezone(
                    request(Map.of("tz", bad)), TZ_QUERY));
            assertThat(firstField(refusal).get("code")).as(bad).isEqualTo("timezone");
        }
    }

    // --- fixtures ---

    /** The bound request context as the RequestBinder leaves it: query, params and a body. */
    private static Exchange request(Map<String, Object> query) {
        Exchange exchange = new Exchange(Beans.NONE);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("query", query);
        context.put("params", query);
        context.put("body", Map.of("report", Map.of("loc", "xx-YY"), "tz", 9));
        exchange.setProperty(TesseraqlProperties.CONTEXT, context);
        return exchange;
    }

    private static Exchange principal(Map<String, Object> claims) {
        Exchange exchange = new Exchange(Beans.NONE);
        exchange.setProperty(TesseraqlProperties.PRINCIPAL, new Principal("u-1", "anne", "Anne",
                null, List.of(), List.of(), List.of(), claims));
        return exchange;
    }

    private static Exchange negotiatedLocale(String tag) {
        Exchange exchange = new Exchange(Beans.NONE);
        exchange.setProperty(TesseraqlProperties.LOCALE, tag);
        return exchange;
    }

    private static TqlException refusal(Supplier<String> call) {
        TqlException refusal = catchThrowableOfType(TqlException.class, call::get);
        assertThat(refusal).as("expected a refusal").isNotNull();
        return refusal;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstField(TqlException refusal) {
        List<Map<String, Object>> fields = (List<Map<String, Object>>) refusal.details()
                .get("fields");
        return fields.get(0);
    }
}
