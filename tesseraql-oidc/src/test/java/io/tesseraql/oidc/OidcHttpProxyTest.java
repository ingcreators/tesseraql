package io.tesseraql.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Regression for the proxy-omission bug (docs/app-developer-distribution.md): the outbound JDK HTTP
 * client must carry a {@link ProxySelector} so the JVM proxy configuration is honored. Before the
 * fix the builder never called {@code .proxy(...)}, so the client ignored even
 * {@code http(s).proxyHost} and could not reach an OpenID Provider behind a proxy. The same fix
 * applies to {@code HttpCallClient} and {@code WebhookNotifier}.
 */
class OidcHttpProxyTest {

    @Test
    void clientHonorsTheJvmProxySelector() throws Exception {
        OidcHttp http = new OidcHttp(Duration.ofSeconds(5));
        Field field = OidcHttp.class.getDeclaredField("client");
        field.setAccessible(true);
        HttpClient client = (HttpClient) field.get(http);
        assertThat(client.proxy()).contains(ProxySelector.getDefault());
    }
}
