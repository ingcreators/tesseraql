package io.tesseraql.core.expr;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * An immutable set of {@link ExpressionFunction} providers extending the expression language's
 * built-in whitelist ({@link Expr.Call#FUNCTIONS}). The set is a value: the parser resolves each
 * custom call against the set it was handed and the parsed tree captures the resolved function,
 * so an expression evaluates with the functions it was parsed under — on any thread, across any
 * reload (docs/module-scope.md).
 *
 * <p>A <em>process default</em> ({@link #install(ClassLoader)} / {@link #processDefault()})
 * serves the single-application invocations — the one-app CLI commands ({@code lint},
 * {@code test}, {@code coverage}, {@code job}, {@code routes}, {@code duckdb}) and the Maven
 * goals — where one process is one application and the default therefore <em>is</em> that
 * application's set. Multi-application processes ({@code host}, {@code dev}, {@code mcp}) pass
 * each application's own set explicitly and never install the default.
 *
 * <p>Construction fails fast (TQL-SQL-2110) on a name that is not a Java identifier, shadows a
 * built-in, or is contributed twice — a broken function jar should stop the command, not
 * silently change which functions expressions resolve.
 */
public final class ExpressionFunctions {

    private static final TqlErrorCode INVALID_FUNCTION = new TqlErrorCode(TqlDomain.SQL, 2110);

    private static final ExpressionFunctions BUILT_INS_ONLY = new ExpressionFunctions(Map.of());

    /** The process default; built-ins only until a single-application entry point installs. */
    private static volatile ExpressionFunctions processDefault = BUILT_INS_ONLY;

    /** The custom functions by name; empty means built-ins only. */
    private final Map<String, ExpressionFunction> custom;

    private ExpressionFunctions(Map<String, ExpressionFunction> custom) {
        this.custom = custom;
    }

    /** The set with no custom functions — only the built-ins parse. */
    public static ExpressionFunctions builtInsOnly() {
        return BUILT_INS_ONLY;
    }

    /** Discovers every {@link ExpressionFunction} provider visible to {@code loader}. */
    public static ExpressionFunctions load(ClassLoader loader) {
        return of(ServiceLoader.load(ExpressionFunction.class, loader));
    }

    /** The set holding exactly the given functions, validated. */
    public static ExpressionFunctions of(Iterable<ExpressionFunction> functions) {
        Map<String, ExpressionFunction> byName = new LinkedHashMap<>();
        for (ExpressionFunction function : functions) {
            String name = function.name();
            if (name == null || !isIdentifier(name)) {
                throw new TqlException(INVALID_FUNCTION, "Expression function name '" + name
                        + "' (" + function.getClass().getName() + ") is not a legal identifier");
            }
            if (Expr.Call.FUNCTIONS.containsKey(name) || isKeyword(name)) {
                throw new TqlException(INVALID_FUNCTION, "Expression function '" + name + "' ("
                        + function.getClass().getName() + ") shadows a built-in");
            }
            if (function.arity() < 0) {
                throw new TqlException(INVALID_FUNCTION, "Expression function '" + name + "' ("
                        + function.getClass().getName() + ") declares a negative arity");
            }
            ExpressionFunction previous = byName.putIfAbsent(name, function);
            if (previous != null) {
                throw new TqlException(INVALID_FUNCTION, "Expression function '" + name
                        + "' is contributed twice: " + previous.getClass().getName() + " and "
                        + function.getClass().getName());
            }
        }
        return byName.isEmpty() ? BUILT_INS_ONLY : new ExpressionFunctions(Map.copyOf(byName));
    }

    /**
     * Installs the process default from every provider visible to {@code loader}, replacing any
     * previous installation. Single-application entry points only.
     */
    public static void install(ClassLoader loader) {
        processDefault = load(loader);
    }

    /** Installs exactly the given functions as the process default (tests, embedded setups). */
    public static void install(Iterable<ExpressionFunction> functions) {
        processDefault = of(functions);
    }

    /** Resets the process default to the built-ins (tests and embedded setups). */
    public static void reset() {
        processDefault = BUILT_INS_ONLY;
    }

    /** The process default: what {@code ExpressionParser.parse(String)} resolves against. */
    public static ExpressionFunctions processDefault() {
        return processDefault;
    }

    /** The arity of a built-in or member function, or {@code null} for an unknown name. */
    public Integer arity(String name) {
        Integer builtin = Expr.Call.FUNCTIONS.get(name);
        if (builtin != null) {
            return builtin;
        }
        ExpressionFunction function = custom.get(name);
        return function == null ? null : function.arity();
    }

    /** The member custom function of that name, or {@code null} (built-ins are not here). */
    public ExpressionFunction custom(String name) {
        return custom.get(name);
    }

    private static boolean isIdentifier(String name) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        return name.chars().skip(1).allMatch(Character::isJavaIdentifierPart);
    }

    /** The expression language's literal keywords, which the parser resolves before calls. */
    private static boolean isKeyword(String name) {
        return name.equals("null") || name.equals("true") || name.equals("false");
    }
}
