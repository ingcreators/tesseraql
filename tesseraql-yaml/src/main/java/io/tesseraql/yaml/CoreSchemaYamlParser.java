package io.tesseraql.yaml;

import java.io.Reader;
import java.util.Locale;
import java.util.Optional;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.events.ScalarEvent;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.resolver.CoreScalarResolver;
import org.snakeyaml.engine.v2.resolver.JsonScalarResolver;
import org.snakeyaml.engine.v2.resolver.ScalarResolver;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.io.IOContext;
import tools.jackson.core.util.BufferRecycler;
import tools.jackson.dataformat.yaml.YAMLParser;

/**
 * A YAML parser that reads plain scalars with YAML 1.2's core schema, the schema the editor
 * applies (docs/jackson-3.md decision 6). Jackson 3.1 fixes the JSON schema in its parser;
 * 3.2 reads the core schema through {@code LoadSettings} (jackson-dataformats-text #627), and
 * when an LTS line carries that, this class and {@link CoreSchemaYamlFactory} are deleted.
 *
 * <p>The override acts only where the two schemas read a scalar differently: {@code ~},
 * {@code Null} and {@code NULL} are null; {@code True}, {@code TRUE}, {@code False} and
 * {@code FALSE} are booleans; {@code 0o}/{@code 0x} integers, decimals with a sign or leading
 * zeros, and floats with a sign, a bare dot or an {@code .inf}/{@code .nan} spelling are
 * numbers. Everything else — explicit tags, quoted scalars, the empty-string rule, every value
 * the schemas agree on — is Jackson's own reading.
 */
final class CoreSchemaYamlParser extends YAMLParser {

    private static final ScalarResolver CORE = new CoreScalarResolver(false);

    private static final ScalarResolver JSON = new JsonScalarResolver();

    CoreSchemaYamlParser(ObjectReadContext readCtxt, IOContext ioCtxt, BufferRecycler br,
            int streamReadFeatures, int formatFeatures, LoadSettings loadSettings,
            Reader reader) {
        super(readCtxt, ioCtxt, br, streamReadFeatures, formatFeatures, loadSettings, reader);
    }

    @Override
    protected JsonToken _decodeScalar(ScalarEvent scalar) throws JacksonException {
        Optional<String> tag = scalar.getTag();
        if (tag.isEmpty() || "!".equals(tag.get())) {
            String value = scalar.getValue();
            boolean plain = scalar.getImplicit().canOmitTagInPlainScalar();
            Tag core = CORE.resolve(value, plain);
            if (!core.equals(JSON.resolve(value, plain))
                    || Tag.FLOAT.equals(core) && infinityOrNaN(value)) {
                JsonToken token = coreToken(core, value);
                if (token != null) {
                    return token;
                }
            }
        }
        return super._decodeScalar(scalar);
    }

    /** The core schema's token for a scalar the JSON schema reads differently, or null. */
    private JsonToken coreToken(Tag core, String value) {
        if (Tag.NULL.equals(core)) {
            _textValue = value;
            _cleanedTextValue = null;
            return JsonToken.VALUE_NULL;
        }
        if (Tag.BOOL.equals(core)) {
            _textValue = value;
            _cleanedTextValue = null;
            return Character.toLowerCase(value.charAt(0)) == 't'
                    ? JsonToken.VALUE_TRUE
                    : JsonToken.VALUE_FALSE;
        }
        if (Tag.INT.equals(core)) {
            _textValue = value;
            _cleanedTextValue = null;
            if (value.startsWith("0o") || value.startsWith("0x")) {
                return _decodeNumberScalar(value, value.length());
            }
            // Not _decodeNumberScalar: it reads a leading-zero decimal as YAML 1.1 octal, and
            // the core schema reads 0123 as 123.
            return decimal(value);
        }
        if (Tag.FLOAT.equals(core)) {
            _textValue = value;
            return decimalFloat(value);
        }
        return null;
    }

    /** {@code [-+]?[0-9]+}, base ten whatever its leading zeros. */
    private JsonToken decimal(String value) {
        boolean negative = value.charAt(0) == '-';
        int start = negative || value.charAt(0) == '+' ? 1 : 0;
        while (start < value.length() - 1 && value.charAt(start) == '0') {
            start++;
        }
        _numberNegative = negative;
        _numTypesValid = 0;
        _cleanedTextValue = (negative ? "-" : "") + value.substring(start);
        return JsonToken.VALUE_NUMBER_INT;
    }

    /**
     * A core-schema float in the form Java parses: no leading {@code +}, a digit before a bare
     * dot, and {@code .inf}/{@code .nan} as {@code Infinity}/{@code NaN}. Jackson's own float
     * cleaner is private.
     */
    private JsonToken decimalFloat(String value) {
        boolean negative = value.charAt(0) == '-';
        String unsigned = negative || value.charAt(0) == '+' ? value.substring(1) : value;
        String lower = unsigned.toLowerCase(Locale.ROOT);
        String cleaned;
        if (".inf".equals(lower)) {
            cleaned = "Infinity";
        } else if (".nan".equals(lower)) {
            cleaned = "NaN";
        } else {
            cleaned = unsigned.startsWith(".") ? "0" + unsigned : unsigned;
        }
        _numberNegative = negative;
        _numTypesValid = 0;
        _cleanedTextValue = (negative ? "-" : "") + cleaned;
        return JsonToken.VALUE_NUMBER_FLOAT;
    }

    private static boolean infinityOrNaN(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.endsWith(".inf") || ".nan".equals(lower);
    }
}
