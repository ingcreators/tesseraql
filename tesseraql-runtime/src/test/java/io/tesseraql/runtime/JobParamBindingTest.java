package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.JobDefinition;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A job's declared {@code params:} are bound before the job runs.
 *
 * <p>They were accepted, documented with a shipped example, and never read. Whatever the caller
 * sent reached the job's SQL uncoerced — a {@code count} arrived as the string {@code "10"} — and
 * a required parameter nobody sent was simply absent until the SQL failed on an unbound one, which
 * names the SQL rather than the missing input.
 *
 * <p>Tested at the binding rather than through a job run because three entry points share it: the
 * ops API, {@code runJob} and the per-tenant variant. Binding in one of them would be the "two
 * spellings, one working" shape this codebase has spent the day removing.
 */
class JobParamBindingTest {

    private static JobFile job(Map<String, InputField> params) {
        return new JobFile(Path.of("batch/probe/job.yml"),
                new JobDefinition("tesseraql/v1", "probe", "job", "batch-pipeline", null, null,
                        params, null, false, null));
    }

    private static InputField param(String type, boolean required) {
        return new InputField(type, required, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void aDeclaredIntegerParameterIsCoerced() {
        Map<String, Object> sent = new LinkedHashMap<>();
        sent.put("count", "10");

        Map<String, Object> bound = TesseraqlRuntime.bindJobParams(
                job(Map.of("count", param("integer", false))), sent);

        assertThat(bound.get("count")).isEqualTo(10L);
    }

    @Test
    void aMissingRequiredParameterIsRefusedBeforeTheJobStarts() {
        JobFile probe = job(Map.of("businessDate", param("string", true)));

        assertThatThrownBy(() -> TesseraqlRuntime.bindJobParams(probe, Map.of()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("businessDate");
    }

    @Test
    void aJobDeclaringNoParametersPassesWhatItWasGiven() {
        Map<String, Object> sent = Map.of("anything", "at all");

        assertThat(TesseraqlRuntime.bindJobParams(job(Map.of()), sent)).isSameAs(sent);
    }
}
