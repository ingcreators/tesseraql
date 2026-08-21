package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every {@code META-INF/services} descriptor this project ships names a class that exists
 * (docs/camel-removal.md slice 6c).
 *
 * <p>Resources are not compiled, so a descriptor is the one place a fully-qualified class name can
 * rot without anything saying so. It happened twice in one slice: four descriptors for a component
 * SPI outlived the classes they named by several slices and shipped inside the jar, and a package
 * rename that a compiler verified across 146 source files left two {@code RuntimeExtension}
 * descriptors pointing at the old package — which surfaced as a {@code ServiceConfigurationError}
 * in an unrelated integration test, because a failed provider lookup fails the whole
 * {@link java.util.ServiceLoader} iteration and takes app startup with it.
 *
 * <p>Scoped to this project's own SPIs by descriptor name: a third-party jar's descriptors are its
 * own business, and one naming an optional provider is allowed to be unresolvable here.
 */
class ServiceDescriptorsResolveTest {

    @Test
    void everyServiceDescriptorNamesAClassThatLoads() throws Exception {
        List<String> broken = new ArrayList<>();
        int checked = 0;
        for (String spi : ourServiceInterfaces()) {
            Enumeration<URL> descriptors = getClass().getClassLoader()
                    .getResources("META-INF/services/" + spi);
            while (descriptors.hasMoreElements()) {
                URL descriptor = descriptors.nextElement();
                for (String provider : providersIn(descriptor)) {
                    checked++;
                    try {
                        Class.forName(provider, false, getClass().getClassLoader());
                    } catch (ClassNotFoundException ex) {
                        broken.add(provider + " (declared in " + descriptor + ")");
                    }
                }
            }
        }

        assertThat(checked)
                .as("the scan has to find descriptors, or it asserts nothing at all")
                .isPositive();
        assertThat(broken)
                .as("a descriptor naming a class that is not there fails app startup, not just "
                        + "the feature it registers")
                .isEmpty();
    }

    /**
     * The SPI names to look up, taken from the interfaces this project declares rather than from a
     * directory walk: {@code ServiceLoader} resolves by resource name, so this asks the classpath
     * the same question the runtime does.
     */
    private static Set<String> ourServiceInterfaces() {
        return new LinkedHashSet<>(List.of(
                "io.tesseraql.compiler.ext.RuntimeExtension",
                "io.tesseraql.yaml.apps.AppSourceProvider",
                "io.tesseraql.yaml.blob.BlobStoreProvider",
                "io.tesseraql.yaml.secret.SecretResolver",
                "io.tesseraql.core.files.FileCodec",
                "io.tesseraql.core.expr.ExpressionFunction",
                "io.tesseraql.pdf.PdfEngine"));
    }

    /** A descriptor's provider lines, with comments and blanks dropped as ServiceLoader does. */
    private static List<String> providersIn(URL descriptor) throws IOException {
        List<String> providers = new ArrayList<>();
        try (InputStream stream = descriptor.openStream()) {
            String body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : body.split("\n")) {
                int comment = line.indexOf('#');
                String name = (comment >= 0 ? line.substring(0, comment) : line).trim();
                if (!name.isEmpty()) {
                    providers.add(name);
                }
            }
        }
        return providers;
    }
}
