package io.tesseraql.yaml;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.json.JsonLimits;
import org.junit.jupiter.api.Test;

/**
 * The constrained mapper actually constrains. Until now the only guard was
 * {@code JsonMapperLedgerTest}'s grep for the construction site — nothing fed the factory a
 * document past its declared bounds, so a builder change that silently dropped a constraint
 * would have kept every test green.
 */
class JsonMappersTest {

    @Test
    void depthAtTheDeclaredBoundParsesAndOnePastItRefuses() {
        ObjectMapper mapper = JsonMappers.constrained();

        assertThatCode(() -> mapper.readTree(nested(JsonLimits.MAX_NESTING_DEPTH)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> mapper.readTree(nested(JsonLimits.MAX_NESTING_DEPTH + 1)))
                .isInstanceOf(StreamConstraintsException.class);
    }

    @Test
    void aFieldNamePastTheDeclaredBoundRefuses() {
        ObjectMapper mapper = JsonMappers.constrained();
        String pastLimit = "{\"" + "a".repeat(JsonLimits.MAX_NAME_LENGTH + 1) + "\":1}";

        assertThatThrownBy(() -> mapper.readTree(pastLimit))
                .isInstanceOf(StreamConstraintsException.class);
    }

    private static String nested(int depth) {
        return "[".repeat(depth) + "]".repeat(depth);
    }
}
