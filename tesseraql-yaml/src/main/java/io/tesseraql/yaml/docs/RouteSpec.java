package io.tesseraql.yaml.docs;

import java.util.List;

/**
 * The documentation-portal spec for a single route or HTML page (documentation portal v1, spec
 * layer). It is a deterministic projection of a {@code RouteDefinition} (plus the SQL it binds)
 * into the facts the portal renders: the request surface, the security declaration, validation and
 * notifications, the response shape, and the bound SQL with its declared binds and control
 * structure. Derived facts only — templates place them, they do not author them.
 *
 * @param id          the route id
 * @param method      the HTTP method (e.g. {@code GET})
 * @param path        the served URL path
 * @param recipe      the recipe driving compilation (e.g. {@code query-json})
 * @param kind        the definition kind ({@code route})
 * @param inputs      declared, whitelisted inputs with their constraints, sorted by name
 * @param security    the security declaration, or {@code null} for a public route
 * @param validations the command's validation rules in authored order
 * @param notifications the command's notifications in authored order
 * @param response    the response shape, or {@code null} when none is declared
 * @param sql         the bound SQL statements: the main {@code sql}, steps, queries, and transfer SQL
 */
public record RouteSpec(
        String id,
        String method,
        String path,
        String recipe,
        String kind,
        List<Input> inputs,
        Security security,
        List<Validation> validations,
        List<Notification> notifications,
        Response response,
        List<SqlStatement> sql) {

    public RouteSpec {
        inputs = List.copyOf(inputs);
        validations = List.copyOf(validations);
        notifications = List.copyOf(notifications);
        sql = List.copyOf(sql);
    }

    /**
     * A declared input parameter and its constraints (absent constraints are {@code null}).
     *
     * @param name         the input name
     * @param type         the declared type, defaulting to {@code string}
     * @param required     whether the input is required
     * @param defaultValue the declared default, or {@code null}
     * @param min          the minimum (numeric inputs), or {@code null}
     * @param max          the maximum (numeric inputs), or {@code null}
     * @param maxLength    the maximum length (string inputs), or {@code null}
     * @param enumValues   the allowed values, or empty
     * @param format       the parse/format pattern (date/number inputs), or {@code null}
     */
    public record Input(String name, String type, boolean required, Object defaultValue,
            java.math.BigDecimal min, java.math.BigDecimal max, Integer maxLength,
            List<String> enumValues, String format) {

        public Input {
            enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        }
    }

    /**
     * The route's security declaration (deny-by-default: a public route has no declaration).
     *
     * @param auth     the authentication type (e.g. {@code bearer}, {@code browser})
     * @param policy   the authorization policy id, or {@code null}
     * @param provider the named provider, or {@code null}
     * @param csrf     whether CSRF protection is required
     */
    public record Security(String auth, String policy, String provider, boolean csrf) {
    }

    /**
     * One validation rule of the command's {@code validate:} block.
     *
     * @param id         the rule id
     * @param kind       {@code expression} or {@code sql}
     * @param expression the cross-field expression, for an {@code expression} rule
     * @param file       the validation SQL file, for a {@code sql} rule
     * @param field      the field path violations report against, or {@code null}
     * @param when       the optional guard expression, or {@code null}
     */
    public record Validation(String id, String kind, String expression, String file, String field,
            String when) {
    }

    /**
     * One notification of the command's {@code notify:} block.
     *
     * @param id      the notification id
     * @param channel the configured channel name
     * @param when    the optional guard expression, or {@code null}
     * @param payload the payload keys carried on the event, in authored order
     */
    public record Notification(String id, String channel, String when, List<String> payload) {

        public Notification {
            payload = payload == null ? List.of() : List.copyOf(payload);
        }
    }

    /**
     * The response shape (the fields irrelevant to {@code kind} are {@code null}).
     *
     * @param kind        {@code json}, {@code html}, {@code stream}, {@code redirect}, or {@code file}
     * @param status      the HTTP status, or {@code null}
     * @param template    the template path (HTML/file responses), or {@code null}
     * @param contentType the content type (stream/file responses), or {@code null}
     * @param location    the redirect target (redirect responses), or {@code null}
     */
    public record Response(String kind, Integer status, String template, String contentType,
            String location) {
    }

    /**
     * One bound SQL statement: the source text plus the binds and control structure parsed from it.
     * Exactly one of {@code file}, {@code contract}, or {@code service} is set.
     *
     * @param label     the statement's role ({@code sql}, {@code step:<id>}, {@code query:<id>},
     *                  {@code import}, {@code export}, {@code export.after})
     * @param file      the SQL file relative to the route directory, or {@code null}
     * @param contract  the Identity SQL Contract name, or {@code null}
     * @param service   the named runtime service provider, or {@code null}
     * @param mode      the effective execution mode (e.g. {@code query}, {@code update})
     * @param statement the raw 2-way SQL text, or {@code null} for a contract/service binding
     * @param binds     the distinct declared bind expressions, in first-seen order
     * @param structure the {@code if}/{@code for}/{@code scope} directives, flattened with depth
     */
    public record SqlStatement(String label, String file, String contract, String service,
            String mode, String statement, List<String> binds, List<Control> structure) {

        public SqlStatement {
            binds = List.copyOf(binds);
            structure = List.copyOf(structure);
        }
    }

    /**
     * One control directive of a 2-way SQL statement, in document order with its nesting depth.
     *
     * @param kind       {@code if}, {@code elseif}, {@code else}, {@code for}, or {@code scope}
     * @param expression the directive source: the condition, {@code item : items}, or scope name;
     *                   {@code null} for {@code else}
     * @param depth      the nesting depth (0 at the top level)
     */
    public record Control(String kind, String expression, int depth) {
    }
}
