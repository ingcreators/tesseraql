package io.tesseraql.core.error;

/**
 * Error domains used in the {@code TQL-<DOMAIN>-<NUMBER>} error taxonomy (design ch. 37).
 *
 * <p>Each domain groups errors raised by a functional area of the framework so that the same
 * code can be referenced uniformly across API responses, logs, reports, Studio, and the
 * Operations Console.
 */
public enum TqlDomain {
    YAML, SQL, CAMEL, SEC, IAM, SAML, SCIM, OIDC, TENANT, APP, FIELD, MASK, RATE, BATCH, SPOOL, OTEL, STUDIO, REPORT, COMPAT, UPGRADE, PLUGIN, PLAN, GOV, LD, LANE, TPL, COMP, IDEM, MCP, WORKFLOW
}
