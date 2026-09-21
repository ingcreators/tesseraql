package io.tesseraql.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What a test copy of {@code examples/procurement-app} needs before a <em>host</em> boots from
 * it — the twin of {@link UserAdminAppCopy} for the second example that declares a module.
 *
 * <p>The example declares {@code tesseraql.modules: [io.tesseraql:tesseraql-pdf]} so
 * {@code tesseraql dev} resolves the printable-documents codec for it; a copy has no resolved
 * {@code work/modules}, and a host refuses an application whose declared modules are not on
 * disk ({@code TQL-APP-4216}). A single {@code TesseraqlRuntime} runs no such guard and finds
 * the codec on this module's test classpath, which is why only the host-shaped tests need this.
 */
final class ProcurementAppCopy {

    private ProcurementAppCopy() {
    }

    static final String MODULES = "  modules:\n    - io.tesseraql:tesseraql-pdf\n";

    /** Call on every copy of {@code examples/procurement-app} before a host boots from it. */
    static void prepare(Path appHome) throws IOException {
        Path config = appHome.resolve("config/tesseraql.yml");
        String declared = Files.readString(config);
        if (!declared.contains(MODULES)) {
            throw new IllegalStateException("The example's module declaration moved; update"
                    + " ProcurementAppCopy so a host fixture does not refuse the copy");
        }
        Files.writeString(config, declared.replace(MODULES, ""));
    }
}
