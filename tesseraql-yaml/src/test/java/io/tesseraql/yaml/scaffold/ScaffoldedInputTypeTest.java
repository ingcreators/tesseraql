package io.tesseraql.yaml.scaffold;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.lint.AppLinter;
import java.lang.reflect.Modifier;
import java.sql.Types;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Every input type the scaffolder can emit is one the framework's vocabulary knows.
 *
 * <p>{@code datetime} was not. {@code TableSchema.Column.inputType()} maps every
 * {@code TIMESTAMP} column to it, {@code CrudScaffolder} writes it into a generated app's
 * {@code input:} block, {@code InputBinder} coerces it, {@code ViewFields} renders it as a
 * {@code datetime-local} widget, and {@code McpInputSchema} publishes it — while
 * {@code AppLinter.knownInputTypes()} and the shipped schema's {@code type} enum listed seven
 * types without it. The costs were an editor marking a scaffolded app's own field invalid, and
 * Studio's type picker (which reads {@code knownInputTypes}) omitting it.
 *
 * <p>The codomain is <strong>derived by driving every {@code java.sql.Types} constant through the
 * mapping</strong>, not by listing what the scaffolder emits today: a JDBC type mapped to a new
 * name reaches this assertion the day it lands.
 */
class ScaffoldedInputTypeTest {

    @Test
    void everyTypeTheScaffolderEmitsIsInTheFrameworkVocabulary() throws Exception {
        Set<String> emitted = new TreeSet<>();
        int jdbcTypes = 0;
        for (var constant : Types.class.getFields()) {
            if (!Modifier.isStatic(constant.getModifiers()) || constant.getType() != int.class) {
                continue;
            }
            jdbcTypes++;
            emitted.add(column(constant.getInt(null)).inputType());
        }

        // Non-vacuity: a reflection walk that stopped finding constants would satisfy the
        // assertion below by emitting nothing at all.
        assertThat(jdbcTypes).as("java.sql.Types was walked").isGreaterThan(20);
        assertThat(emitted).as("the mapping produced a vocabulary").hasSizeGreaterThan(3);

        assertThat(new LinkedHashSet<>(AppLinter.knownInputTypes()))
                .as("every input type a scaffolded application can carry is one the linter, the"
                        + " editor schema and Studio's type picker all know: %s", emitted)
                .containsAll(emitted);
    }

    private static TableSchema.Column column(int jdbcType) {
        return new TableSchema.Column("c", jdbcType, "t", 0, 0, true, false, false);
    }
}
