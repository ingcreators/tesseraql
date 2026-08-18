package io.tesseraql.pdf;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Engine selection (roadmap Phase 21): engines register through {@link ServiceLoader} and the
 * {@code tesseraql.pdf.engine} system property picks one per deployment - a packaging call,
 * not a per-route option. The default (and the adopted ch. 50 engine) is {@code openhtml}.
 */
final class PdfEngines {

    static final String PROPERTY = "tesseraql.pdf.engine";
    static final String DEFAULT = "openhtml";

    private static final TqlErrorCode UNKNOWN_ENGINE = new TqlErrorCode(TqlDomain.LD, 2833);

    private PdfEngines() {
    }

    static PdfEngine selected() {
        String id = System.getProperty(PROPERTY, DEFAULT);
        List<String> available = new ArrayList<>();
        // Resolved against this class's own defining loader, not the TCCL: when the pdf jar
        // arrives as a module, the engine sits in the same jar as the codec that calls this,
        // and the thread's context loader knows nothing about it (docs/module-scope.md).
        for (PdfEngine engine : ServiceLoader.load(PdfEngine.class,
                PdfEngines.class.getClassLoader())) {
            if (engine.id().equals(id)) {
                return engine;
            }
            available.add(engine.id());
        }
        throw new TqlException(UNKNOWN_ENGINE, "No pdf engine '" + id + "' - available: "
                + available + " (set -D" + PROPERTY + " to one of them)");
    }
}
