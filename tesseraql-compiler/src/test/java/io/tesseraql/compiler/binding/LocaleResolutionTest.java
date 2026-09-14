package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.security.Principal;
import io.tesseraql.yaml.i18n.I18nSettings;
import io.tesseraql.yaml.i18n.MessageCatalog;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocaleResolutionTest {

    private static RuntimeContext context;

    @BeforeAll
    static void start() throws Exception {
        context = new RuntimeContext();
        context.start();
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    private static final I18nSettings SETTINGS = new I18nSettings("en", List.of("en", "ja"),
            List.of("principal.claim.locale"), MessageCatalog.empty());

    private static Exchange exchange() {
        return new Exchange(context.beans());
    }

    private static String resolved(Exchange exchange) {
        new LocaleResolution(SETTINGS).process(exchange);
        return exchange.getProperty(TesseraqlProperties.LOCALE, String.class);
    }

    @Test
    void principalPreferenceWinsOverAcceptLanguage() {
        Exchange exchange = exchange();
        exchange.setProperty(TesseraqlProperties.PRINCIPAL, principalWithLocale("ja"));
        exchange.request().header("Accept-Language", "en");

        assertThat(resolved(exchange)).isEqualTo("ja");
    }

    @Test
    void regionPreferenceMatchesBareLanguage() {
        Exchange exchange = exchange();
        exchange.setProperty(TesseraqlProperties.PRINCIPAL, principalWithLocale("ja-JP"));

        assertThat(resolved(exchange)).isEqualTo("ja");
    }

    @Test
    void unsupportedPreferenceFallsThroughToAcceptLanguage() {
        Exchange exchange = exchange();
        exchange.setProperty(TesseraqlProperties.PRINCIPAL, principalWithLocale("de-DE"));
        exchange.request().header("Accept-Language", "fr;q=0.9, ja;q=0.8");

        assertThat(resolved(exchange)).isEqualTo("ja");
    }

    @Test
    void acceptLanguageNegotiatesByQuality() {
        Exchange exchange = exchange();
        exchange.request().header("Accept-Language", "ja;q=0.9, en;q=1.0");

        assertThat(resolved(exchange)).isEqualTo("en");
    }

    @Test
    void appDefaultAppliesWithoutPreferenceOrHeader() {
        assertThat(resolved(exchange())).isEqualTo("en");
    }

    @Test
    void malformedAcceptLanguageFallsBackToDefault() {
        Exchange exchange = exchange();
        exchange.request().header("Accept-Language", ";;;not-a-header");

        assertThat(resolved(exchange)).isEqualTo("en");
    }

    /** The stored account preference (roadmap Phase 48) beats the IdP claim in default order. */
    @Test
    void storedPreferenceBeatsThePrincipalClaim() {
        context.bind(TesseraqlProperties.PREFERENCE_STORE_BEAN,
                new io.tesseraql.core.account.PreferenceStore() {
                    @Override
                    public Map<String, String> preferences(String tenantId, String subject) {
                        return "u-1".equals(subject)
                                ? Map.of("ui.locale", "ja")
                                : Map.of();
                    }

                    @Override
                    public void put(String tenantId, String subject, String key, String value) {
                    }

                    @Override
                    public void remove(String tenantId, String subject, String key) {
                    }
                });
        try {
            I18nSettings settings = new I18nSettings("en", List.of("en", "ja"),
                    List.of("preference.ui.locale", "principal.claim.locale"),
                    MessageCatalog.empty());
            Exchange exchange = exchange();
            exchange.setProperty(TesseraqlProperties.PRINCIPAL, principalWithLocale("en"));

            new LocaleResolution(settings).process(exchange);

            assertThat(exchange.getProperty(TesseraqlProperties.LOCALE, String.class))
                    .isEqualTo("ja");
        } finally {
            context.unbind(TesseraqlProperties.PREFERENCE_STORE_BEAN);
        }
    }

    /**
     * The framework's own Japanese is reachable with no configuration: an app with no
     * {@code messages/} directory and no {@code locales:} still serves the built-in {@code ja}
     * catalog (the module that owns {@code tesseraql/messages/ja.yml} is this one), so a
     * browser's {@code Accept-Language: ja} negotiates to {@code ja} and the framework's
     * chrome, error texts and input messages render in it. The served set used to be the
     * app's files alone, so the header negotiated to English by default.
     */
    @Test
    void theBuiltInJapaneseNegotiatesWithoutAnAppCatalog(@TempDir java.nio.file.Path home) {
        I18nSettings settings = I18nSettings.from(
                new io.tesseraql.yaml.config.AppConfig(Map.of()), home);
        assertThat(settings.supportedTags()).containsExactly("en", "ja");
        assertThat(settings.catalog().resolve("ja", "tql.view.empty"))
                .isEqualTo("データがありません");

        Exchange exchange = exchange();
        exchange.request().header("Accept-Language", "ja, en;q=0.5");
        new LocaleResolution(settings).process(exchange);

        assertThat(exchange.getProperty(TesseraqlProperties.LOCALE, String.class))
                .isEqualTo("ja");
    }

    @Test
    void queryParameterSourceReadsTheRequest() {
        I18nSettings settings = new I18nSettings("en", List.of("en", "ja"),
                List.of("query.lang"), MessageCatalog.empty());
        Exchange exchange = exchange();
        exchange.request().queryParams().put("lang", java.util.List.of("ja"));

        new LocaleResolution(settings).process(exchange);

        assertThat(exchange.getProperty(TesseraqlProperties.LOCALE, String.class))
                .isEqualTo("ja");
    }

    private static Principal principalWithLocale(String tag) {
        return new Principal("u-1", "anne", "Anne", null, List.of(), List.of(), List.of(),
                Map.of("locale", tag));
    }
}
