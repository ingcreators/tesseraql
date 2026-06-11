package io.tesseraql.yaml.secret;

/**
 * A pluggable secret provider (design ch. 41), addressed from configuration as
 * {@code ${secret.<provider>.<name>}}. Implementations are discovered via
 * {@link java.util.ServiceLoader} alongside the built-in {@code env} and {@code file} providers,
 * so a vault integration is a jar on the classpath.
 *
 * <p>Resolved secret values are used literally - they never go through another round of
 * placeholder expansion - and must never be written to logs, generated artifacts or evidence
 * documents.
 */
public interface SecretResolver {

    /** The provider id used in placeholders ({@code env}, {@code file}, ...). */
    String provider();

    /** The secret value for {@code name}, or {@code null} when this provider does not have it. */
    String resolve(String name);
}
