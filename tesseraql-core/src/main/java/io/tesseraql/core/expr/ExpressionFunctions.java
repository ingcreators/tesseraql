package io.tesseraql.core.expr;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * The process-wide registry of {@link ExpressionFunction} providers extending the expression
 * language's built-in whitelist ({@link Expr.Call#FUNCTIONS}). Commands install it once at start
 * ({@code serve}, {@code lint}, {@code test}, {@code coverage}, {@code mcp}, the Maven goals)
 * from the composed modules classloader; until then only the built-ins parse. Installation is an
 * explicit, deterministic snapshot — deliberately not a per-thread context-classloader lookup,
 * so hot-reload and worker threads always see the same function set.
 *
 * <p>Installation fails fast (TQL-SQL-2110) on a name that is not a Java identifier, shadows a
 * built-in, or is contributed twice — a broken function jar should stop the command, not
 * silently change which functions expressions resolve.
 */
public final class ExpressionFunctions {

    private static final TqlErrorCode INVALID_FUNCTION = new TqlErrorCode(TqlDomain.SQL, 2110);

    /** The installed custom functions by name; empty (built-ins only) until installed. */
    private static volatile Map<String, ExpressionFunction> custom = Map.of();

    private ExpressionFunctions() {
    }

    /**
     * Discovers every {@link ExpressionFunction} provider visible to {@code loader} and installs
     * the set, replacing any previous installation.
     */
    public static void install(ClassLoader loader) {
        install(ServiceLoader.load(ExpressionFunction.class, loader));
    }

    /** Installs exactly the given functions (used by tests and embedded setups). */
    public static void install(Iterable<ExpressionFunction> functions) {
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
        custom = Map.copyOf(byName);
    }

    /** Uninstalls every custom function, back to the built-ins (tests and embedded setups). */
    public static void reset() {
        custom = Map.of();
    }

    /** The arity of a built-in or installed function, or {@code null} for an unknown name. */
    public static Integer arity(String name) {
        Integer builtin = Expr.Call.FUNCTIONS.get(name);
        if (builtin != null) {
            return builtin;
        }
        ExpressionFunction function = custom.get(name);
        return function == null ? null : function.arity();
    }

    /** The installed custom function of that name, or {@code null} (built-ins are not here). */
    public static ExpressionFunction custom(String name) {
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
