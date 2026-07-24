package io.tesseraql.yaml.domain;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.model.DomainsDocument;
import io.tesseraql.yaml.model.ErrorsSpec;
import io.tesseraql.yaml.model.InputField;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The app's field domains (docs/field-domains.md): named, app-level definitions of business
 * fields — type, constraints, format, classification — declared once under {@code domains/} and
 * referenced from any route's {@code input:} block via {@code domain:}. The manifest loader
 * resolves references at load time, so the input binder, error model, OpenAPI emission, and
 * coverage consume plain fully-populated fields, unchanged.
 *
 * <pre>{@code
 * # domains/catalog.yml
 * version: tesseraql/v1
 * domains:
 *   sku:
 *     type: string
 *     maxLength: 40
 *     pattern: "[A-Z0-9-]+"
 * constraints:
 *   uq_products_sku:
 *     field: sku
 *     code: duplicate
 * }</pre>
 *
 * <p>A domain describes the field itself; the route describes the operation's use of it. The
 * operational keys — {@code required}, {@code requiredWhen}, {@code default}, {@code writable} —
 * are rejected inside a domain ({@code TQL-FIELD-4602}), so a domain can never silently make a
 * field mandatory application-wide. The {@code constraints:} map is the app-level constraint
 * catalog: database constraint names mapped once, inherited by every route, route-local
 * {@code errors.constraints} entries winning by name.
 */
public final class FieldDomains {

    private static final TqlErrorCode DUPLICATE = new TqlErrorCode(TqlDomain.FIELD, 4600);
    private static final TqlErrorCode UNKNOWN = new TqlErrorCode(TqlDomain.FIELD, 4601);

    private final Map<String, InputField> domains;
    private final Map<String, ErrorsSpec.ConstraintMapping> constraints;

    private FieldDomains(Map<String, InputField> domains,
            Map<String, ErrorsSpec.ConstraintMapping> constraints) {
        this.domains = java.util.Collections.unmodifiableMap(domains);
        this.constraints = java.util.Collections.unmodifiableMap(constraints);
    }

    /**
     * Loads every {@code domains/*.yml} document under the app home into one app-wide namespace;
     * a name declared twice — domain or constraint — is a build error ({@code TQL-FIELD-4600}).
     */
    public static FieldDomains load(Path appHome) {
        Path dir = appHome.resolve("domains");
        Map<String, InputField> domains = new LinkedHashMap<>();
        Map<String, ErrorsSpec.ConstraintMapping> constraints = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            return new FieldDomains(domains, constraints);
        }
        SimpleYamlParser parser = new SimpleYamlParser();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(file -> file.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .forEach(file -> {
                        DomainsDocument document = parser.parseDomains(file);
                        document.domains().forEach((name, field) -> {
                            if (domains.putIfAbsent(name, field) != null) {
                                throw new TqlException(DUPLICATE, "Domain '" + name
                                        + "' is declared twice (second: " + file + ")");
                            }
                        });
                        document.constraints().forEach((name, mapping) -> {
                            if (constraints.putIfAbsent(name, mapping) != null) {
                                throw new TqlException(DUPLICATE, "Constraint '" + name
                                        + "' is mapped twice (second: " + file + ")");
                            }
                        });
                    });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new FieldDomains(domains, constraints);
    }

    public boolean isEmpty() {
        return domains.isEmpty() && constraints.isEmpty();
    }

    /** The declared domains by name. */
    public Map<String, InputField> domains() {
        return domains;
    }

    /** The app-level constraint catalog: DB constraint name to its field mapping. */
    public Map<String, ErrorsSpec.ConstraintMapping> constraints() {
        return constraints;
    }

    /** The named domain, or a build error naming the referencing source. */
    public InputField require(String name, String source) {
        InputField domain = domains.get(name);
        if (domain == null) {
            throw new TqlException(UNKNOWN, source + " references unknown domain '" + name
                    + "' — declare it under domains/ or fix the reference");
        }
        return domain;
    }
}
