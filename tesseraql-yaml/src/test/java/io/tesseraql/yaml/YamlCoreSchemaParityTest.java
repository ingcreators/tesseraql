package io.tesseraql.yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * docs/jackson-3.md decision 6: every YAML read goes through {@link YamlMappers}, which reads a
 * plain scalar as YAML 1.2's core schema does (the YAML 1.2.2 spec, 10.3.2) — the reading the
 * editor applies through {@code redhat.vscode-yaml}. Jackson 3.1 alone reads the JSON schema,
 * under which {@code ~}, {@code True} and {@code 0x1F} are text: a plain {@code YAMLFactory}
 * behind the mapper turns the null, boolean and integer rows here red, and handing a
 * leading-zero decimal to Jackson's own number decoder reads {@code 0123} as octal 83.
 */
class YamlCoreSchemaParityTest {

    private static final ObjectMapper YAML = YamlMappers.constrained();

    private static JsonNode value(String scalar) {
        return YAML.readTree("v: " + scalar + "\n").get("v");
    }

    @ParameterizedTest
    @ValueSource(strings = {"~", "null", "Null", "NULL", ""})
    void coreNullsAreNull(String scalar) {
        assertThat(value(scalar).isNull()).as("v: %s", scalar).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"true,true", "True,true", "TRUE,true", "false,false", "False,false",
            "FALSE,false"})
    void coreBooleansAreBooleans(String scalar, boolean expected) {
        JsonNode node = value(scalar);
        assertThat(node.isBoolean()).as("v: %s", scalar).isTrue();
        assertThat(node.booleanValue()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "-19,-19", "+12,12", "0123,123", "-0123,-123", "007,7", "0o14,12",
            "0x1F,31", "0xff,255"})
    void coreIntegersAreIntegers(String scalar, long expected) {
        JsonNode node = value(scalar);
        assertThat(node.isIntegralNumber()).as("v: %s", scalar).isTrue();
        assertThat(node.longValue()).as("v: %s", scalar).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"1.5,1.5", "+1.5,1.5", ".5,0.5", "-.5,-0.5", "1.,1.0", "-1e3,-1000.0",
            "6.8523015e+5,685230.15"})
    void coreFloatsAreFloats(String scalar, double expected) {
        JsonNode node = value(scalar);
        assertThat(node.isFloatingPointNumber()).as("v: %s", scalar).isTrue();
        assertThat(node.doubleValue()).as("v: %s", scalar).isEqualTo(expected);
    }

    @Test
    void coreInfinitiesAndNotANumber() {
        assertThat(value(".inf").doubleValue()).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(value("+.Inf").doubleValue()).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(value("-.INF").doubleValue()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(value(".nan").doubleValue()).isNaN();
        assertThat(value(".NaN").doubleValue()).isNaN();
    }

    /** YAML 1.1's booleans, binary, underscores and base 60 are text under the core schema. */
    @ParameterizedTest
    @ValueSource(strings = {"yes", "no", "on", "off", "Yes", "OFF", "y", "n", "0b101", "1_000",
            "1:30", "0x", "0o8", "12e", "tRue", "nULL", "<<"})
    void everythingElseIsText(String scalar) {
        JsonNode node = value(scalar);
        assertThat(node.isString()).as("v: %s", scalar).isTrue();
        assertThat(node.asString()).isEqualTo(scalar);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"~\"", "'true'", "\"0x1F\"", "'null'", "\"0123\""})
    void aQuotedScalarIsText(String scalar) {
        assertThat(value(scalar).isString()).as("v: %s", scalar).isTrue();
    }

    @Test
    void anExplicitTagIsJacksonsOwnReading() {
        assertThat(value("!!str 123").asString()).isEqualTo("123");
        assertThat(value("!!str ~").asString()).isEqualTo("~");
    }

    record Code(String code) {
    }

    record Flag(boolean on) {
    }

    @Test
    void aStringFieldKeepsTheDigitsAsWritten() {
        assertThat(YAML.readValue("code: 0123\n", Code.class).code()).isEqualTo("0123");
        assertThat(YAML.readValue("code: 0x1F\n", Code.class).code()).isEqualTo("0x1F");
    }

    @Test
    void aBooleanFieldTakesTheCoreSpellingsAndRefusesTheRest() {
        assertThat(YAML.readValue("on: True\n", Flag.class).on()).isTrue();
        assertThatThrownBy(() -> YAML.readValue("on: yes\n", Flag.class))
                .isInstanceOf(JacksonException.class);
    }
}
