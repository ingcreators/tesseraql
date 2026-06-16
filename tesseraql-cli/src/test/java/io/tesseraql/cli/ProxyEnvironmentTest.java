package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProxyEnvironmentTest {

    @Test
    void bridgesHttpHttpsAndNoProxyToJvmProperties() {
        Map<String, String> env = Map.of(
                "HTTP_PROXY", "http://proxy.local:3128",
                "HTTPS_PROXY", "http://user:pass@proxy.local:3129",
                "NO_PROXY", ".internal,localhost");
        Map<String, String> props = new HashMap<>();

        ProxyEnvironment.apply(env, props::get, props::put);

        assertThat(props).containsEntry("http.proxyHost", "proxy.local")
                .containsEntry("http.proxyPort", "3128")
                .containsEntry("https.proxyHost", "proxy.local")
                .containsEntry("https.proxyPort", "3129")
                .containsEntry("https.proxyUser", "user")
                .containsEntry("https.proxyPassword", "pass")
                .containsEntry("http.nonProxyHosts", "*.internal|localhost");
    }

    @Test
    void defaultsThePortAndAcceptsBareHostPort() {
        Map<String, String> env = Map.of("HTTPS_PROXY", "proxy.local");
        Map<String, String> props = new HashMap<>();

        ProxyEnvironment.apply(env, props::get, props::put);

        assertThat(props).containsEntry("https.proxyHost", "proxy.local")
                .containsEntry("https.proxyPort", "443");
    }

    @Test
    void neverOverwritesAnExplicitProperty() {
        Map<String, String> env = Map.of("HTTP_PROXY", "http://env.proxy:8080");
        Map<String, String> props = new HashMap<>(Map.of("http.proxyHost", "explicit.proxy"));

        ProxyEnvironment.apply(env, props::get, props::put);

        assertThat(props).containsEntry("http.proxyHost", "explicit.proxy");
        assertThat(props).doesNotContainKey("http.proxyPort");
    }

    @Test
    void noProxyEnvLeavesPropertiesUntouched() {
        Map<String, String> props = new HashMap<>();
        ProxyEnvironment.apply(Map.of(), props::get, props::put);
        assertThat(props).isEmpty();
    }
}
