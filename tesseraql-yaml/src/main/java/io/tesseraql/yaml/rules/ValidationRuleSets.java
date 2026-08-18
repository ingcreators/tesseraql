package io.tesseraql.yaml.rules;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.sql.AmbientBinds;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
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
    private static final TqlErrorCode DECLARED_CONTRACT = new TqlErrorCode(TqlDomain.FIELD, 4609);

    private final Path rulesDir;
    private final Map<String, RuleSetsDocument.RuleSet> rules;

    private ValidationRuleSets(Path rulesDir, Map<String, RuleSetsDocument.RuleSet> rules) {
        this.rulesDir = rulesDir;
        this.rules = java.util.Collections.unmodifiableMap(rules);
    }

    /** Loads every {@code rules/*.yml} under the app home; duplicate names fail the load. */
    public static ValidationRuleSets load(Path appHome, SimpleYamlParser parser) {
        return load(appHome, parser, io.tesseraql.core.expr.ExpressionFunctions.processDefault());
    }

    /** As {@link #load(Path, SimpleYamlParser)}, resolving custom calls against {@code functions}. */
    public static ValidationRuleSets load(Path appHome, SimpleYamlParser parser,
            io.tesseraql.core.expr.ExpressionFunctions functions) {
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
        rules.forEach((name, rule) -> {
            checkDeclaredTypes(name, rule);
            checkDeclaredContract(dir, name, rule, functions);
        });
        return new ValidationRuleSets(dir, rules);
    }

    /** The scalar types a bind may declare — the input-field vocabulary, arrays excluded. */
    private static final Set<String> BIND_TYPES = Set.of("string", "integer", "number",
            "boolean", "date");

    /** A typed contract must use the input-field type vocabulary. */
    private static void checkDeclaredTypes(String name, RuleSetsDocument.RuleSet rule) {
        rule.binds().forEach((bind, type) -> {
            if (type == null || !BIND_TYPES.contains(type)) {
                throw new TqlException(DECLARED_CONTRACT, "Rule '" + name + "' bind '" + bind
                        + "' declares type '" + type + "' — one of " + BIND_TYPES);
            }
        });
    }

    /**
     * Checks a rule's {@code binds:} against what its SQL actually binds.
     *
     * <p>Every reference was checked against the contract; the contract itself was checked
     * against nothing. Adding a bind to a shared rule's SQL without extending {@code binds:}
     * therefore passed load and lint everywhere and failed on the first request that triggered
     * the rule — the one failure mode a shared declaration is supposed to make impossible,
     * because the whole point is that N routes agree with one definition.
     */
    private static void checkDeclaredContract(Path rulesDir, String name,
            RuleSetsDocument.RuleSet rule, io.tesseraql.core.expr.ExpressionFunctions functions) {
        if (rule.file() == null) {
            // An expression rule reads the route's own inputs; there is nothing to wire, so a
            // contract on one is a mistake that today dies per-reference at compile time.
            if (!rule.binds().isEmpty()) {
                throw new TqlException(DECLARED_CONTRACT, "Rule '" + name
                        + "' declares binds: " + rule.binds()
                        + " but is an expression rule — only a file: rule has a bind contract");
            }
            return;
        }
        Path sqlFile = rulesDir.resolve(rule.file()).normalize();
        if (!Files.isRegularFile(sqlFile)) {
            // The missing file is its own error at resolution; do not report it as a contract.
            return;
        }
        Set<String> actual = new LinkedHashSet<>();
        try {
            collectBinds(Sql2WayParser.parse(Files.readString(sqlFile), functions), actual);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (TqlException unparseable) {
            // Malformed SQL is reported where SQL is parsed for execution, with its own code.
            return;
        }
        actual.removeIf(AmbientBinds::isAmbient);
        Set<String> declared = new LinkedHashSet<>(rule.binds().keySet());
        if (!declared.equals(actual)) {
            throw new TqlException(DECLARED_CONTRACT, "Rule '" + name + "' declares binds "
                    + declared + " but " + rule.file() + " binds " + actual
                    + " — the contract every reference is checked against must match the SQL");
        }
    }

    /** Every bind the template can reach, branches included: a contract covers all paths. */
    private static void collectBinds(java.util.List<SqlNode> nodes, Set<String> binds) {
        for (SqlNode node : nodes) {
            switch (node) {
                case SqlNode.Bind bind -> binds.add(bind.expressionSource().trim());
                case SqlNode.ListBind bind -> binds.add(bind.expressionSource().trim());
                case SqlNode.If conditional -> conditional.branches()
                        .forEach(branch -> collectBinds(branch.body(), binds));
                case SqlNode.For loop -> collectBinds(loop.body(), binds);
                default -> {
                }
            }
        }
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
        return resolve(id, declared, routeDir, source, Map.of());
    }

    /**
     * As {@link #resolve(String, ValidationRule, Path, String)}, additionally checking each
     * wired {@code params.<field>} expression against the contract's declared bind type when
     * the referencing route declares that input's type (docs/vocabulary-cleanup.md slice 2).
     */
    public ValidationRule resolve(String id, ValidationRule declared, Path routeDir,
            String source, Map<String, io.tesseraql.yaml.model.InputField> routeInput) {
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
        Set<String> contract = new LinkedHashSet<>(shared.binds().keySet());
        if (!declared.params().keySet().equals(contract)) {
            throw new TqlException(CONTRACT, source + ": validation rule '" + id
                    + "' must wire exactly the binds " + contract + " of rule '"
                    + declared.use() + "', not " + declared.params().keySet());
        }
        shared.binds().forEach((bind, type) -> {
            String expr = declared.params().get(bind);
            if (expr == null || !expr.startsWith("params.")) {
                return;
            }
            io.tesseraql.yaml.model.InputField field = routeInput
                    .get(expr.substring("params.".length()));
            if (field != null && field.type() != null && !type.equals(field.type())) {
                throw new TqlException(CONTRACT, source + ": validation rule '" + id
                        + "' wires '" + bind + ": " + expr + "' (" + field.type()
                        + ") against rule '" + declared.use() + "' which declares '" + bind
                        + ": " + type + "' — the typed contract must match the input");
            }
        });
        String routeRelativeFile = shared.file() == null
                ? null
                : routeDir.relativize(rulesDir.resolve(shared.file()).normalize())
                        .toString().replace('\\', '/');
        return declared.mergedWith(shared.rule(), routeRelativeFile, shared.code(),
                shared.message());
    }
}
