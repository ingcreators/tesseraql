package io.tesseraql.core.sql;

/**
 * A single positional bind parameter produced while rendering 2-way SQL (design ch. 8.2).
 *
 * @param expression the directive expression that produced this parameter, e.g. {@code q}
 * @param value      the resolved value to bind to the {@code ?} placeholder
 * @param sourceLine the 1-based line in the source SQL where the directive appears
 */
public record BoundParameter(String expression, Object value, int sourceLine) {
}
