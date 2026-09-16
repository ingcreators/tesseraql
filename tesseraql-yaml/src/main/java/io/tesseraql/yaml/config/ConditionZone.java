package io.tesseraql.yaml.config;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.app.ExportDeclarations;
import java.time.ZoneId;
import java.util.Optional;

/**
 * The zone a role's hours conditions are judged in, {@code tesseraql.security.conditions.zone}
 * (docs/access-governance.md structural decision 8) — read as it is written, the way the
 * files zone is (docs/export-declarations.md decision 14), and judged by the zone predicate
 * every other declared zone shares. The key used to be handed to {@link ZoneId#of} bare, so a
 * misspelt region ({@code Asia/Tokio}) took the boot down with the JDK's own sentence, naming
 * neither the key nor the application, and no lint read it at all. Now the linter reports it
 * and the boot refuses it from the same predicate.
 */
public final class ConditionZone {

    /** The configuration key. */
    public static final String KEY = "tesseraql.security.conditions.zone";

    /**
     * TQL-SEC-4147: {@code tesseraql.security.conditions.zone} is not a time-zone id the JDK
     * knows. Reported at lint and refused at boot from the same predicate.
     */
    public static final TqlErrorCode INVALID = new TqlErrorCode(TqlDomain.SEC, 4147);

    private ConditionZone() {
    }

    /**
     * The declared zone, or empty when the key is absent or blank — the "absent means the
     * JVM's" reading the runtime binds by. A value that is not a zone is refused.
     */
    public static Optional<ZoneId> of(AppConfig config) {
        String value = value(config);
        if (value == null) {
            return Optional.empty();
        }
        problem(value).ifPresent(problem -> {
            throw new TqlException(INVALID, problem);
        });
        return Optional.of(ZoneId.of(value));
    }

    /** Why the declared zone is refused, or empty when it is absent or a zone the JDK knows. */
    public static Optional<String> problem(AppConfig config) {
        String value = value(config);
        return value == null ? Optional.empty() : problem(value);
    }

    private static Optional<String> problem(String value) {
        return ExportDeclarations.zoneProblem(value).map(problem -> KEY + ": " + problem);
    }

    /**
     * The literal as declared; a placeholder this environment cannot resolve is the
     * deployment's to supply and reads as absent, the way the files keys do.
     */
    private static String value(AppConfig config) {
        try {
            // Spelled out, not KEY: the configuration reference scans for the literal beside
            // the accessor, and this is the key's one read site.
            return config.getString("tesseraql.security.conditions.zone")
                    .filter(value -> !value.isBlank())
                    .orElse(null);
        } catch (TqlException unresolved) {
            return null;
        }
    }
}
