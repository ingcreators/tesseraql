package io.tesseraql.cli;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bridges the container/CI-standard {@code HTTP_PROXY} / {@code HTTPS_PROXY} / {@code NO_PROXY}
 * environment variables to the JVM proxy system properties the JDK actually reads
 * ({@code http(s).proxyHost}/{@code Port}, {@code http.nonProxyHosts}). The JDK ignores those env
 * vars by default, so without this bridge the CLI's outbound paths — the embedded module resolver
 * and the runtime's {@code HttpClient}s (which honor {@code ProxySelector.getDefault()}) — cannot
 * reach the network through a proxy configured only by environment (design:
 * app-developer-distribution.md, proxy support).
 *
 * <p>Precedence: an explicitly set system property always wins and is never overwritten, so
 * {@code -Dhttp.proxyHost}, {@code ~/.m2/settings.xml} {@code <proxies>}, or a flag take priority
 * over the env bridge.
 */
final class ProxyEnvironment {

    private ProxyEnvironment() {
    }

    /** Applies the env bridge to the JVM system properties (idempotent; never overwrites). */
    static void bridgeFromEnvironment() {
        apply(System.getenv(), System::getProperty, System::setProperty);
    }

    /** The bridge logic over an env source and a property store, for testing. */
    static void apply(Map<String, String> env, Function<String, String> getProperty,
            BiConsumer<String, String> setProperty) {
        bridgeProxy(env, "HTTP_PROXY", "http", 80, getProperty, setProperty);
        bridgeProxy(env, "HTTPS_PROXY", "https", 443, getProperty, setProperty);

        String noProxy = firstNonBlank(env.get("NO_PROXY"), env.get("no_proxy"));
        if (noProxy != null && getProperty.apply("http.nonProxyHosts") == null) {
            String hosts = Arrays.stream(noProxy.split(","))
                    .map(String::trim).filter(host -> !host.isEmpty())
                    .map(ProxyEnvironment::toNonProxyGlob)
                    .collect(Collectors.joining("|"));
            if (!hosts.isEmpty()) {
                setProperty.accept("http.nonProxyHosts", hosts);
            }
        }
    }

    private static void bridgeProxy(Map<String, String> env, String variable, String scheme,
            int defaultPort, Function<String, String> getProperty,
            BiConsumer<String, String> setProperty) {
        String value = firstNonBlank(env.get(variable), env.get(variable.toLowerCase(Locale.ROOT)));
        if (value == null || getProperty.apply(scheme + ".proxyHost") != null) {
            return;
        }
        URI uri = parse(value);
        if (uri == null || uri.getHost() == null) {
            return;
        }
        setProperty.accept(scheme + ".proxyHost", uri.getHost());
        setProperty.accept(scheme + ".proxyPort",
                Integer.toString(uri.getPort() != -1 ? uri.getPort() : defaultPort));
        if (uri.getUserInfo() != null) {
            String[] credentials = uri.getUserInfo().split(":", 2);
            setProperty.accept(scheme + ".proxyUser", credentials[0]);
            if (credentials.length == 2) {
                setProperty.accept(scheme + ".proxyPassword", credentials[1]);
            }
        }
    }

    private static URI parse(String value) {
        try {
            return URI.create(value.contains("://") ? value : "http://" + value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** {@code NO_PROXY} ".example.com"/"example.com" → the JVM glob "*.example.com"/"example.com". */
    private static String toNonProxyGlob(String host) {
        return host.startsWith(".") ? "*" + host : host;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }
}
