package io.tesseraql.yaml.rules;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.model.RuleSetsDocument;
import io.tesseraql.yaml.model.ValidationRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The app's shared validation rules (docs/validation-rule-sets.md): named cross-field or SQL
 * rules declared once under {@code rules/}, referenced from any command's {@code validate:}
 * block via {@code use:}. The manifest loader resolves references at load time, so rule
 * execution, the error model, validation coverage, and testing consume plain
 * {@link ValidationRule}s unchanged.
 *
 * <p>The domain/route line, applied to rules: the set carries what the rule <em>is</em>
 * (expression or SQL, bind contract, default code/message); every reference carries its own
 * {@code params:} wiring — checked against the contract — plus the local {@code field:} target
 * and {@code when:} guard. Ambient binds ({@code principal.*}, {@code audit.*}) seed shared
 * SQL exactly as route SQL, so they never appear in a contract.
 */
public final class ValidationRuleSets {

    private static final TqlErrorCode DUPLICATE = new TqlErrorCode(TqlDomain.FIELD, 4605);
    private static final TqlErrorCode UNKNOWN = new TqlErrorCode(TqlDomain.FIELD, 4606);
    private static final TqlErrorCode CONTRACT = new TqlErrorCode(TqlDomain.FIELD, 4607);
    private static final TqlErrorCode CONFLICT = new TqlErrorCode(TqlDomain.FIELD, 4608);

    private final Path rulesDir;
    private final Map<String, RuleSetsDocument.RuleSet> rules;

    private ValidationRuleSets(Path rulesDir, Map<String, RuleSetsDocument.RuleSet> rules) {
        this.rulesDir = rulesDir;
        this.rules = java.util.Collections.unmodifiableMap(rules);
    }

    /** Loads every {@code rules/*.yml} under the app home; duplicate names fail the load. */
    public static ValidationRuleSets load(Path appHome, SimpleYamlParser parser) {
        Path dir = appHome.resolve("rules");
        Map<String, RuleSetsDocument.RuleSet> rules = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            return new ValidationRuleSets(dir, rules);
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(file -> file.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .forEach(file -> parser.parseRuleSets(file).rules()
                            .forEach((name, rule) -> {
                                if (rules.putIfAbsent(name, rule) != null) {
                                    throw new TqlException(DUPLICATE, "Rule '" + name
                                            + "' is declared twice (second: " + file + ")");
                                }
                            }));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new ValidationRuleSets(dir, rules);
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** The declared rules by name (for lint and the docs portal). */
    public Map<String, RuleSetsDocument.RuleSet> rules() {
        return rules;
    }

    /**
     * Resolves one route-declared rule: a plain rule passes through; a {@code use:} reference
     * merges the shared rule underneath, with the SQL path rewritten relative to the
     * referencing route's directory and the {@code params:} wiring checked against the bind
     * contract exactly.
     */
    public ValidationRule resolve(String id, ValidationRule declared, Path routeDir,
            String source) {
        if (declared.use() == null || declared.use().isBlank()) {
            return declared;
        }
        if (declared.isExpression() || declared.isSql()) {
            throw new TqlException(CONFLICT, source + ": validation rule '" + id
                    + "' declares use: together with rule:/file: — a reference carries only"
                    + " its local wiring");
        }
        RuleSetsDocument.RuleSet shared = rules.get(declared.use());
        if (shared == null) {
            throw new TqlException(UNKNOWN, source + ": validation rule '" + id
                    + "' references unknown rule '" + declared.use()
                    + "' — declare it under rules/ or fix the reference");
        }
        Set<String> contract = new LinkedHashSet<>(shared.binds());
        if (!declared.params().keySet().equals(contract)) {
            throw new TqlException(CONTRACT, source + ": validation rule '" + id
                    + "' must wire exactly the binds " + contract + " of rule '"
                    + declared.use() + "', not " + declared.params().keySet());
        }
        String routeRelativeFile = shared.file() == null
                ? null
                : routeDir.relativize(rulesDir.resolve(shared.file()).normalize())
                        .toString().replace('\\', '/');
        return declared.mergedWith(shared.rule(), routeRelativeFile, shared.code(),
                shared.message());
    }
}
