package io.tesseraql.yaml;

import java.io.CharArrayReader;
import java.io.InputStream;
import java.io.Reader;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.io.IOContext;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLFactoryBuilder;
import tools.jackson.dataformat.yaml.YAMLParser;

/**
 * The YAML factory whose parsers read YAML 1.2's core schema ({@link CoreSchemaYamlParser},
 * docs/jackson-3.md decision 6). A copied or rebuilt factory stays this class: the builder's
 * {@code build()} and the factory's {@code copy()}/{@code rebuild()} are overridden, since the
 * library's own build a plain {@code YAMLFactory}.
 */
final class CoreSchemaYamlFactory extends YAMLFactory {

    private static final long serialVersionUID = 1L;

    private CoreSchemaYamlFactory(Builder builder) {
        super(builder);
    }

    private CoreSchemaYamlFactory(CoreSchemaYamlFactory source) {
        super(source);
    }

    /** A builder whose {@code build()} makes this factory. */
    static YAMLFactoryBuilder coreSchemaBuilder() {
        return new Builder();
    }

    @Override
    public YAMLFactoryBuilder rebuild() {
        return new Builder(this);
    }

    @Override
    public CoreSchemaYamlFactory copy() {
        return new CoreSchemaYamlFactory(this);
    }

    @Override
    protected Object readResolve() {
        return new CoreSchemaYamlFactory(this);
    }

    @Override
    protected YAMLParser _createParser(ObjectReadContext readCtxt, IOContext ioCtxt,
            InputStream in) {
        return parser(readCtxt, ioCtxt, _createReader(in, null, ioCtxt));
    }

    @Override
    protected YAMLParser _createParser(ObjectReadContext readCtxt, IOContext ioCtxt, Reader r) {
        return parser(readCtxt, ioCtxt, r);
    }

    @Override
    protected YAMLParser _createParser(ObjectReadContext readCtxt, IOContext ioCtxt,
            char[] data, int offset, int len, boolean recyclable) {
        return parser(readCtxt, ioCtxt, new CharArrayReader(data, offset, len));
    }

    @Override
    protected YAMLParser _createParser(ObjectReadContext readCtxt, IOContext ioCtxt,
            byte[] data, int offset, int len) {
        return parser(readCtxt, ioCtxt, _createReader(data, offset, len, null, ioCtxt));
    }

    private YAMLParser parser(ObjectReadContext readCtxt, IOContext ioCtxt, Reader reader) {
        return new CoreSchemaYamlParser(readCtxt, ioCtxt, _getBufferRecycler(),
                readCtxt.getStreamReadFeatures(_streamReadFeatures),
                readCtxt.getFormatReadFeatures(_formatReadFeatures),
                _loadSettings, reader);
    }

    /** The library's builder, building this factory instead of a plain one. */
    private static final class Builder extends YAMLFactoryBuilder {

        Builder() {
            super();
        }

        Builder(CoreSchemaYamlFactory base) {
            super(base);
        }

        @Override
        public YAMLFactory build() {
            return new CoreSchemaYamlFactory(this);
        }
    }
}
