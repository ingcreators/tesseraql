package io.tesseraql.runtime;

import io.tesseraql.core.expr.ExpressionFunction;
import java.util.List;

/**
 * Two same-named {@link ExpressionFunction} providers with different semantics — the collision
 * decision 28 makes structurally impossible (docs/module-scope.md). The classes sit on the test
 * classpath; each fixture module jar carries only a {@code META-INF/services} entry naming one
 * of them, so which one an application's registry holds is decided by its own {@code
 * work/modules} and nothing else.
 */
final class ModuleFixtureFunctions {

    private ModuleFixtureFunctions() {
    }

    public static final class GreetsA implements ExpressionFunction {
        @Override
        public String name() {
            return "shopgreets";
        }

        @Override
        public int arity() {
            return 0;
        }

        @Override
        public Object apply(List<Object> args) {
            return "from-module-a";
        }
    }

    public static final class GreetsB implements ExpressionFunction {
        @Override
        public String name() {
            return "shopgreets";
        }

        @Override
        public int arity() {
            return 0;
        }

        @Override
        public Object apply(List<Object> args) {
            return "from-module-b";
        }
    }
}
