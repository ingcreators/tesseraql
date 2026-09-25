package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.json.JsonLimits;
import io.tesseraql.security.SecurityJson;
import io.tesseraql.yaml.JsonMappers;
import io.tesseraql.yaml.YamlMappers;
import java.lang.reflect.Method;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.EnumFeature;

/**
 * The mapper factories' configuration ledger (docs/jackson-3.md decision 4). Every factory —
 * {@code JsonMappers}, {@code YamlMappers}, and the two below {@code tesseraql-yaml} that
 * repeat its lines, {@code SecurityJson} and {@code McpJson} — carries Jackson 2's value for
 * each default Jackson 3 changed that an application would observe, and the read bounds of
 * {@link JsonLimits}; the YAML one detects duplicate keys and reads the core schema; the ASCII
 * one escapes.
 *
 * <p>OpenRewrite's migration recipe discarded the factory at five of the seven constructions
 * that took one, and every property here went with it while the code still compiled (the
 * record's row 11). A factory built as a bare {@code new JsonMapper()}, or one pinned state
 * flipped, is red here.
 */
class JacksonDefaultsLedgerTest {

    static Stream<Named<ObjectMapper>> factories() throws ReflectiveOperationException {
        // McpJson is package-private in tesseraql-mcp, which sits below tesseraql-yaml.
        Method mcp = Class.forName("io.tesseraql.mcp.McpJson").getDeclaredMethod("constrained");
        mcp.setAccessible(true);
        return Stream.of(
                Named.of("JsonMappers.constrained()", JsonMappers.constrained()),
                Named.of("JsonMappers.constrainedAscii()", JsonMappers.constrainedAscii()),
                Named.of("YamlMappers.constrained()", YamlMappers.constrained()),
                Named.of("SecurityJson.constrained()", SecurityJson.constrained()),
                Named.of("McpJson.constrained()", (ObjectMapper) mcp.invoke(null)));
    }

    @ParameterizedTest
    @MethodSource("factories")
    void jackson2sObservableDefaultsArePinned(ObjectMapper mapper) {
        DeserializationConfig read = mapper.deserializationConfig();
        // An absent boolean in authored YAML means false: refused, permanently.
        assertThat(read.isEnabled(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)).isFalse();
        // Adopted in S2; until then off, as in Jackson 2.
        assertThat(read.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).isFalse();
        // An unknown property stays a refusal: refused, permanently.
        assertThat(read.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
        // Declaration order is the deterministic-output contract: refused, permanently.
        assertThat(mapper.isEnabled(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)).isFalse();
        // An enum's wire form is its name: refused.
        assertThat(read.isEnabled(EnumFeature.READ_ENUMS_USING_TO_STRING)).isFalse();
        assertThat(mapper.serializationConfig().isEnabled(EnumFeature.WRITE_ENUMS_USING_TO_STRING))
                .isFalse();
    }

    @ParameterizedTest
    @MethodSource("factories")
    void theReadBoundsAreJsonLimits(ObjectMapper mapper) {
        StreamReadConstraints bounds = mapper.tokenStreamFactory().streamReadConstraints();
        assertThat(bounds.getMaxNestingDepth()).isEqualTo(JsonLimits.MAX_NESTING_DEPTH);
        assertThat(bounds.getMaxStringLength()).isEqualTo(JsonLimits.MAX_STRING_LENGTH);
        assertThat(bounds.getMaxNameLength()).isEqualTo(JsonLimits.MAX_NAME_LENGTH);
    }

    @Test
    void theYamlBoundHoldsAtTheLimit() {
        ObjectMapper yaml = YamlMappers.constrained();
        assertThatCode(() -> yaml.readTree(nested(JsonLimits.MAX_NESTING_DEPTH)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> yaml.readTree(nested(JsonLimits.MAX_NESTING_DEPTH + 1)))
                .isInstanceOf(StreamConstraintsException.class);
    }

    @Test
    void theYamlFactoryRefusesADuplicateKeyAndReadsTheCoreSchema() {
        ObjectMapper yaml = YamlMappers.constrained();
        assertThat(
                yaml.tokenStreamFactory().isEnabled(StreamReadFeature.STRICT_DUPLICATE_DETECTION))
                .isTrue();
        assertThatThrownBy(() -> yaml.readTree("main: 1\nmain: 2\n"))
                .isInstanceOf(JacksonException.class);
        assertThat(yaml.readTree("v: ~\n").get("v").isNull()).isTrue();
        // An empty value is null: YAMLFactory.builder() drops this default in 3.1.
        assertThat(yaml.readTree("v:\n").get("v").isNull()).isTrue();
    }

    @Test
    void theAsciiMapperEscapesAboveSevenBits() {
        assertThat(JsonMappers.constrainedAscii().writeValueAsString("café 受注"))
                .isEqualTo("\"caf\\u00E9 \\u53D7\\u6CE8\"");
        assertThat(JsonMappers.constrained().writeValueAsString("café")).isEqualTo("\"café\"");
    }

    /** A YAML flow sequence nested {@code depth} deep. */
    private static String nested(int depth) {
        return "[".repeat(depth) + "]".repeat(depth);
    }
}
