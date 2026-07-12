package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.security.Principal;
import io.tesseraql.yaml.i18n.I18nSettings;
import io.tesseraql.yaml.i18n.MessageCatalog;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LocaleResolutionTest {

    private static DefaultCamelContext camel;

    @BeforeAll
    static void start() {
        camel = new DefaultCamelContext();
        camel.start();
    }

    @AfterAll
    static void stop() {
        camel.stop();
    }

    private static final I18nSettings SETTINGS = new I18nSettings("en", List.of("en", "ja"),
            List.of("principal.claim.locale"), MessageCatalog.empty());

    private static Exchange exchange() {
        return new DefaultExchange(camel);
    }

    private static String resolved(Exchange exchange) {
        new LocaleResolution(SETTINGS).process(exchange);
        return exchange.getProperty(TesseraqlProperties.LOCALE, String.class);
    }

    @Test
    void principalPreferenceWinsOverAcceptLanguage() {
        Exchange exchange = exchange();
        exchange.setProperty(TesseraqlProperties.PRINCIPAL, principalWithLocale("ja"));
        exchange.getMessage().setHeader("Accept-Language", "en");

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
        exchange.getMessage().setHeader("Accept-Language", "fr;q=0.9, ja;q=0.8");

        assertThat(resolved(exchange)).isEqualTo("ja");
    }

    @Test
    void acceptLanguageNegotiatesByQuality() {
        Exchange exchange = exchange();
        exchange.getMessage().setHeader("Accept-Language", "ja;q=0.9, en;q=1.0");

        assertThat(resolved(exchange)).isEqualTo("en");
    }

    @Test
    void appDefaultAppliesWithoutPreferenceOrHeader() {
        assertThat(resolved(exchange())).isEqualTo("en");
    }

    @Test
    void malformedAcceptLanguageFallsBackToDefault() {
        Exchange exchange = exchange();
        exchange.getMessage().setHeader("Accept-Language", ";;;not-a-header");

        assertThat(resolved(exchange)).isEqualTo("en");
    }

    /** The stored account preference (roadmap Phase 48) beats the IdP claim in default order. */
    @Test
    void storedPreferenceBeatsThePrincipalClaim() {
        camel.getRegistry().bind(TesseraqlProperties.PREFERENCE_STORE_BEAN,
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
            camel.getRegistry().unbind(TesseraqlProperties.PREFERENCE_STORE_BEAN);
        }
    }

    @Test
    void queryParameterSourceReadsTheRequest() {
        I18nSettings settings = new I18nSettings("en", List.of("en", "ja"),
                List.of("query.lang"), MessageCatalog.empty());
        Exchange exchange = exchange();
        exchange.getMessage().setHeader("lang", "ja");

        new LocaleResolution(settings).process(exchange);

        assertThat(exchange.getProperty(TesseraqlProperties.LOCALE, String.class))
                .isEqualTo("ja");
    }

    private static Principal principalWithLocale(String tag) {
        return new Principal("u-1", "anne", "Anne", null, List.of(), List.of(), List.of(),
                Map.of("locale", tag));
    }
}
