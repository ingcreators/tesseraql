package io.tesseraql.yaml.app;

import io.tesseraql.core.cache.HoldSpec;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.util.Durations;
import io.tesseraql.yaml.app.ExportDeclarations.Kind;
import io.tesseraql.yaml.app.ExportDeclarations.Violation;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.ResultCacheSpec;
import io.tesseraql.yaml.model.RouteDefinition;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where a source's {@code cache:} can hold anything (docs/caching.md decision 6): a
 * {@code sql:} file arm in {@code mode: query}, on a source of a read surface — a
 * {@code query-json}, {@code query-html} or {@code page} route, or an MCP tool that does not
 * write — with a positive {@code maxAge} and the tables the statement reads. Judged at lint
 * and refused at build from this one classification, so the two altitudes cannot disagree.
 *
 * <p>A transactional surface is refused because a hold inside or after a write is a stale
 * read of that write; an export because its statement streams and is never materialized; a
 * contract, service, HTTP or spool arm because there is no statement text to key. A
 * {@code maxAge} is required because an opt-in with a default is a default, and
 * {@code tables:} because a hold no write can reach is the defect the declaration exists to
 * prevent — the {@code file:} catalog's own rule ({@code TQL-FIELD-4621}).
 */
public final class HeldSources {

    /**
     * TQL-YAML-1077: a source's {@code cache:} where nothing can be held — a transactional or
     * streaming surface, a step, a contract, service, HTTP or spool arm, a mode other than
     * query, a missing, unparseable or non-positive {@code maxAge}, a missing or blank
     * {@code tables:} entry. The hold would serve nothing, or serve a write its own request
     * made; the linter and the build name the source and the reason.
     */
    public static final TqlErrorCode NOTHING_TO_HOLD = new TqlErrorCode(TqlDomain.YAML, 1077);

    /** The route recipes whose sources a hold may serve. */
    private static final Set<String> READ_RECIPES = Set.of("query-json", "query-html", "page");

    /** The route recipes that write on the request's own transaction. */
    private static final Set<String> TRANSACTIONAL_RECIPES = Set.of("command-json", "webhook",
            "queue-consume", "file-import");

    /** The route recipes whose statement streams to a codec and is never materialized. */
    private static final Set<String> STREAMING_RECIPES = Set.of("query-export", "file-export");

    private HeldSources() {
    }

    /**
     * Every {@code cache:} of {@code definition} that can hold nothing, with the reason. The
     * surface is judged first; a source on a surface that admits no hold is not judged further,
     * because the fix is to move or drop the declaration, not to mend it.
     */
    public static List<Violation> violations(String app, RouteDefinition definition,
            RecipeShape.Surface surface) {
        List<Violation> out = new ArrayList<>();
        String subject = surface.noun() + " '" + ExportDeclarations.bounded(definition.id())
                + "'";
        String surfaceRefusal = surfaceRefusal(definition, surface);
        definition.sources().forEach((name, binding) -> {
            if (binding.cache() == null) {
                return;
            }
            String key = "sources." + name + ".cache";
            String head = prefix(app, subject, key);
            if (surfaceRefusal != null) {
                out.add(new Violation(NOTHING_TO_HOLD, Kind.INVALID, key,
                        head + surfaceRefusal));
                return;
            }
            declaration(head, key, binding, out);
        });
        definition.steps().forEach((name, binding) -> {
            if (binding.cache() != null) {
                String key = "steps." + name + ".cache";
                out.add(new Violation(NOTHING_TO_HOLD, Kind.INVALID, key, prefix(app, subject,
                        key) + "holds nothing on a step - a step runs inside the command's"
                        + " transaction and reads the write; a hold is legal on the sources"
                        + " of a query-json, query-html or page route"));
            }
        });
        return out;
    }

    /** Why this surface admits no hold at all, or {@code null} when it does. */
    private static String surfaceRefusal(RouteDefinition definition,
            RecipeShape.Surface surface) {
        String recipe = definition.recipe();
        return switch (surface) {
            case CONSUMER -> "holds nothing on a consumer - it writes on its own transaction;"
                    + " a hold is legal on the sources of a query-json, query-html or page"
                    + " route, or of an MCP tool that does not write";
            case TOOL -> transactional(definition)
                    ? "holds nothing on an MCP tool that writes - a hold inside or after the"
                            + " write is a stale read of it; a hold is legal on the sources of a"
                            + " tool that only reads"
                    : null;
            case ROUTE -> {
                if (recipe == null || READ_RECIPES.contains(recipe)) {
                    yield null;
                }
                String because = TRANSACTIONAL_RECIPES.contains(recipe)
                        ? "a hold inside or after the write is a stale read of it"
                        : STREAMING_RECIPES.contains(recipe)
                                ? "its statement streams to the codec and is never held in"
                                        + " memory"
                                : "only a read route's rows can be held";
                yield "holds nothing on a '" + recipe + "' route - " + because + "; a hold is"
                        + " legal on the sources of a query-json, query-html or page route";
            }
        };
    }

    /** The declaration's own arms: the statement, its mode, {@code maxAge}, {@code tables}. */
    private static void declaration(String head, String key, Binding binding,
            List<Violation> out) {
        if (!binding.isSql()) {
            out.add(new Violation(NOTHING_TO_HOLD, Kind.INVALID, key, head
                    + "needs a statement to key - the " + arm(binding)
                    + " has no rendered text and no binds; a hold is legal on a"
                    + " sql: { file: ... } arm"));
            return;
        }
        if (!"query".equals(binding.effectiveMode())) {
            out.add(new Violation(NOTHING_TO_HOLD, Kind.INVALID, key, head
                    + "holds a query's rows - mode: " + binding.effectiveMode()
                    + " produces none to hold"));
        }
        ResultCacheSpec cache = binding.cache();
        if (cache.maxAge() == null || cache.maxAge().isBlank()) {
            out.add(new Violation(NOTHING_TO_HOLD, Kind.INVALID, key, head
                    + "declares no maxAge: - nothing is held unless the source says for how"
                    + " long (30s, 5m)"));
        } else {
            try {
                if (Durations.toMillis(cache.maxAge()) <= 0) {
                    out.add(new Violation(NOTHING_TO_HOLD, Kind.INVALID, key, head
                            + "maxAge '" + ExportDeclarations.bounded(cache.maxAge())
                            + "' is not positive - a hold of no time holds nothing"));
                }
            } catch (RuntimeException notADuration) {
                out.add(new Violation(NOTHING_TO_HOLD, Kind.INVALID, key, head
                        + "maxAge '" + ExportDeclarations.bounded(cache.maxAge())
                        + "' is not a duration (30s, 5m, 1h)"));
            }
        }
        if (cache.tables().isEmpty()) {
            out.add(new Violation(NOTHING_TO_HOLD, Kind.INVALID, key, head
                    + "declares no tables: - a writer's invalidates: names them, and a hold"
                    + " no write can reach is the defect the declaration exists to prevent"));
        } else if (cache.tables().stream().anyMatch(table -> table == null
                || table.isBlank())) {
            out.add(new Violation(NOTHING_TO_HOLD, Kind.INVALID, key, head
                    + "tables: carries a blank name"));
        }
    }

    /** The arm a non-statement binding declares, for the refusal's wording. */
    private static String arm(Binding binding) {
        if (binding.isContract()) {
            return "contract: arm";
        }
        if (binding.isService()) {
            return "service: arm";
        }
        if (binding.declaresHttp()) {
            return "http: arm";
        }
        if (binding.isSpool()) {
            return "spool: arm";
        }
        return "binding";
    }

    /**
     * Whether a document writes on the request's own transaction — the compiler's own test
     * ({@code usesTransactionalCommand}), restated for the tool surface, whose recipe is not
     * what decides it.
     */
    static boolean transactional(RouteDefinition definition) {
        return definition.outbox() != null
                || !definition.validate().isEmpty()
                || !definition.notifications().isEmpty()
                || definition.publish() != null
                || definition.steps().values().stream()
                        .anyMatch(step -> step.file() != null || step.isSequence());
    }

    /**
     * The tables every held source of the application reads, in declaration order — what a
     * command's {@code invalidates:} may name beside a catalog's tables
     * ({@code TQL-FIELD-4620}), and what the runtime stamps.
     */
    public static Set<String> tables(AppManifest manifest) {
        Set<String> tables = new LinkedHashSet<>();
        manifest.routes().forEach(route -> tables.addAll(tables(route.definition())));
        manifest.tools().forEach(tool -> tables.addAll(tables(tool.definition())));
        return tables;
    }

    /** The tables {@code definition}'s held sources read, in declaration order. */
    public static Set<String> tables(RouteDefinition definition) {
        Set<String> tables = new LinkedHashSet<>();
        for (Binding binding : definition.sources().values()) {
            if (binding.cache() != null) {
                binding.cache().tables().stream()
                        .filter(table -> table != null && !table.isBlank())
                        .forEach(tables::add);
            }
        }
        return tables;
    }

    /** Whether any source of the application declares a hold. */
    public static boolean any(AppManifest manifest) {
        return manifest.routes().stream().anyMatch(route -> any(route.definition().sources()))
                || manifest.tools().stream().anyMatch(tool -> any(tool.definition().sources()));
    }

    private static boolean any(Map<String, Binding> sources) {
        return sources.values().stream().anyMatch(binding -> binding.cache() != null);
    }

    /**
     * What a judged declaration compiles to, or {@code null} for a source never held. Called
     * after {@link #violations} refused the malformed ones, so the duration parses and the
     * tables are there.
     *
     * @param owner      the route or tool id
     * @param source     the source's name
     * @param binding    the source
     * @param datasource the connector the statement runs on, before tenant routing
     */
    public static HoldSpec spec(String owner, String source, Binding binding,
            String datasource) {
        ResultCacheSpec cache = binding.cache();
        if (cache == null) {
            return null;
        }
        return new HoldSpec(owner, source, datasource, Durations.toMillis(cache.maxAge()),
                cache.tables());
    }

    private static String prefix(String app, String subject, String key) {
        return "app '" + ExportDeclarations.bounded(app) + "': " + subject + " " + key + ": ";
    }
}
