package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.sql.LockBinding;
import io.tesseraql.pipeline.Beans;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.model.LockSpec;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The lock fields the request binder let past, read and typed by the step that owns them
 * (docs/edit-conflict.md decision 4).
 */
class LockBinderTest {

    private static final LockSpec INTEGER = new LockSpec("version", "integer");
    private static final LockSpec OPAQUE = LockSpec.of("version");

    private static Exchange exchange(Map<String, Object> body) {
        Exchange exchange = new Exchange(new Beans() {
            @Override
            public <T> T lookup(String name, Class<T> type) {
                return null;
            }
        });
        Map<String, Object> context = new HashMap<>();
        context.put("body", body);
        exchange.setProperty(TesseraqlProperties.CONTEXT, context);
        return exchange;
    }

    private static LockBinding bind(Map<String, Object> body, LockSpec lock) throws Exception {
        Exchange exchange = exchange(body);
        new LockBinder("items.update", lock).process(exchange);
        return exchange.getProperty(LockBinder.LOCK_PROPERTY, null, LockBinding.class);
    }

    @Test
    void aFormStringAndAJsonNumberTypeIdentically() throws Exception {
        // The declared type decides, never the class the value happened to arrive as.
        assertThat(bind(Map.of("_lock", "7"), INTEGER).value()).isEqualTo(7L);
        assertThat(bind(Map.of("_lock", 7), INTEGER).value()).isEqualTo(7L);
    }

    @Test
    void anOpaqueLockPassesThroughUntouched() throws Exception {
        assertThat(bind(Map.of("_lock", "01HQ8"), OPAQUE).value()).isEqualTo("01HQ8");
    }

    @Test
    void aMalformedValueIsThisStepRefusalRatherThanAFieldErrorNamingTheHiddenField() {
        // The coercion the binder lends us reports against the field name it was given, and a
        // violation naming _lock would address a form control that does not exist.
        assertThatThrownBy(() -> bind(Map.of("_lock", "abc"), INTEGER))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-FIELD-2011")
                .hasMessageContaining("not a valid integer");
    }

    @Test
    void aDuplicatedFormKeyIsNotALockValue() {
        assertThatThrownBy(() -> bind(Map.of("_lock", java.util.List.of("1", "2")), INTEGER))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("must be a single value");
    }

    @Test
    void theWaiverIsPresenceRatherThanAParsedBoolean() throws Exception {
        // The dialog's Overwrite button carries value="1", and Boolean.parseBoolean("1") is
        // false — which would drop every overwrite silently.
        LockBinding waived = bind(Map.of("_overwrite", "1"), INTEGER);
        assertThat(waived.waived()).isTrue();
        assertThat(waived.value()).isNull();
    }

    @Test
    void theWaiverAndTheLockArriveTogetherOnARealOverwrite() throws Exception {
        // htmx and the native form both serialize the form's own fields alongside the
        // submitter's name and value, so this is the normal case rather than an ambiguity.
        assertThat(bind(Map.of("_lock", "3", "_overwrite", "1"), INTEGER).waived()).isTrue();
    }

    @Test
    void anExplicitNoIsNotAWaiver() throws Exception {
        assertThat(bind(Map.of("_lock", "3", "_overwrite", "false"), INTEGER).waived()).isFalse();
        assertThat(bind(Map.of("_lock", "3", "_overwrite", "0"), INTEGER).waived()).isFalse();
    }

    @Test
    void neitherFieldIsARefusalBeforeTheStatementRuns() {
        assertThatThrownBy(() -> bind(Map.of("name", "x"), INTEGER))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-FIELD-2011")
                .hasMessageContaining("neither _lock nor _overwrite");
    }
}
