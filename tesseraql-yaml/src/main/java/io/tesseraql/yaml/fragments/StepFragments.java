package io.tesseraql.yaml.fragments;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.FragmentUse;
import io.tesseraql.yaml.model.FragmentsDocument;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The app's shared step fragments (docs/transactional-writes.md, "Shared step fragments"): named
 * step sequences declared once under {@code fragments/}, referenced from any command's
 * {@code steps:} through the {@code use:} arm, and expanded at manifest load so everything
 * downstream — the transaction, coverage, spans, lint — sees ordinary steps.
 *
 * <p>The {@code rules/} shape, applied to steps: the fragment carries what the sequence
 * <em>is</em> plus a typed {@code binds:} contract; the reference carries the id it expands
 * under and the {@code params:} wiring. One hop only — a fragment that used another would be the
 * {@code include:} chain the document model deliberately does not have.
 */
public final class StepFragments {

    /** TQL-YAML-1059: a fragment name is declared twice across {@code fragments/}. */
    private static final TqlErrorCode DUPLICATE = new TqlErrorCode(TqlDomain.YAML, 1059);

    /** TQL-YAML-1060: a {@code use:} names a fragment nothing declares. */
    private static final TqlErrorCode UNKNOWN = new TqlErrorCode(TqlDomain.YAML, 1060);

    /** TQL-YAML-1061: a reference's {@code params:} does not satisfy the declared contract. */
    private static final TqlErrorCode CONTRACT = new TqlErrorCode(TqlDomain.YAML, 1061);

    /** TQL-YAML-1062: a fragment uses another, or an expansion collides with a declared step. */
    private static final TqlErrorCode INVALID = new TqlErrorCode(TqlDomain.YAML, 1062);

    private final Path dir;
    private final Map<String, FragmentsDocument.Fragment> fragments;
    private final Map<String, Path> sources;

    private StepFragments(Path dir, Map<String, FragmentsDocument.Fragment> fragments,
            Map<String, Path> sources) {
        this.dir = dir;
        this.fragments = java.util.Collections.unmodifiableMap(fragments);
        this.sources = java.util.Collections.unmodifiableMap(sources);
    }

    /** Loads every {@code fragments/*.yml} under the app home; a duplicate name fails the load. */
    public static StepFragments load(Path appHome, SimpleYamlParser parser) {
        Path dir = appHome.resolve("fragments");
        Map<String, FragmentsDocument.Fragment> fragments = new LinkedHashMap<>();
        Map<String, Path> sources = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            return new StepFragments(dir, fragments, sources);
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(file -> file.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .forEach(file -> parser.parseFragments(file).fragments()
                            .forEach((name, fragment) -> {
                                if (fragments.putIfAbsent(name, fragment) != null) {
                                    throw new TqlException(DUPLICATE, "Fragment '" + name
                                            + "' is declared twice (second: " + file + ")");
                                }
                                sources.put(name, file);
                            }));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        fragments.forEach(StepFragments::checkOneHop);
        return new StepFragments(dir, fragments, sources);
    }

    /** One hop, never a chain — the delegation precedent, applied to sequences. */
    private static void checkOneHop(String name, FragmentsDocument.Fragment fragment) {
        fragment.steps().forEach((id, step) -> {
            if (step.usesFragment()) {
                throw new TqlException(INVALID, "Fragment '" + name + "' step '" + id
                        + "' uses fragment '" + step.use().fragment()
                        + "' — a fragment is one hop, never a chain, so that a reader of the"
                        + " expansion sees the whole of it");
            }
        });
    }

    public boolean isEmpty() {
        return fragments.isEmpty();
    }

    /** The declared fragments by name. */
    public Map<String, FragmentsDocument.Fragment> fragments() {
        return fragments;
    }

    /**
     * The document's steps with every {@code use:} expanded in place.
     *
     * <p>Expansion is textual and total: a fragment step's id becomes {@code <ref>_<step>}, its
     * {@code binds.<name>} params become the reference's wiring for that name, and its SQL file
     * is re-pathed relative to the referencing document so the compiler resolves it exactly as
     * it resolves a colocated one. What comes out is an ordinary step map; nothing downstream
     * can tell.
     *
     * @param steps  the document's authored steps
     * @param source the referencing document, for error messages and for re-pathing SQL
     */
    public Map<String, Binding> expand(Map<String, Binding> steps, Path source) {
        if (steps.values().stream().noneMatch(Binding::usesFragment)) {
            return steps;
        }
        Map<String, Binding> expanded = new LinkedHashMap<>();
        steps.forEach((id, step) -> {
            if (!step.usesFragment()) {
                put(expanded, id, step, source);
                return;
            }
            FragmentUse use = step.use();
            FragmentsDocument.Fragment fragment = fragments.get(use.fragment());
            if (fragment == null) {
                throw new TqlException(UNKNOWN, source + " step '" + id
                        + "' uses unknown fragment '" + use.fragment()
                        + "' — declare it under fragments/ or fix the reference");
            }
            checkContract(id, use, fragment, source);
            fragment.steps().forEach((stepId, declared) -> put(expanded, id + "_" + stepId,
                    rebind(declared, use, source), source));
        });
        return expanded;
    }

    /** A step id is a name; two steps sharing one leaves a reference meaning either. */
    private static void put(Map<String, Binding> into, String id, Binding step, Path source) {
        if (into.putIfAbsent(id, step) != null) {
            throw new TqlException(INVALID, source + ": expanding a fragment produced step '"
                    + id + "', which the document already declares — rename the reference");
        }
    }

    /** The reference's wiring must satisfy the contract exactly: no missing bind, no extra. */
    private void checkContract(String id, FragmentUse use, FragmentsDocument.Fragment fragment,
            Path source) {
        java.util.Set<String> declared = fragment.binds().keySet();
        java.util.Set<String> supplied = use.params().keySet();
        java.util.List<String> missing = declared.stream()
                .filter(bind -> !supplied.contains(bind)).sorted().toList();
        java.util.List<String> extra = supplied.stream()
                .filter(bind -> !declared.contains(bind)).sorted().toList();
        if (!missing.isEmpty() || !extra.isEmpty()) {
            throw new TqlException(CONTRACT, source + " step '" + id + "' does not satisfy the"
                    + " contract of fragment '" + use.fragment() + "'"
                    + (missing.isEmpty() ? "" : " — missing " + missing)
                    + (extra.isEmpty() ? "" : " — undeclared " + extra));
        }
    }

    /**
     * One fragment step, wired to this reference: {@code binds.<name>} params take the
     * reference's value for that name, and the SQL file is re-pathed from the referencing
     * document's directory to the fragment's, where it is colocated.
     */
    private Binding rebind(Binding step, FragmentUse use, Path source) {
        Map<String, String> params = new LinkedHashMap<>();
        step.params().forEach((bind, expr) -> params.put(bind, resolveBind(expr, use)));
        String file = step.file() == null ? null : repath(step.file(), use.fragment(), source);
        return new Binding(file, step.contract(), step.mode(), params, step.service(),
                step.http(), step.materialize(), step.sequence(), step.keys(), step.expect(),
                step.timeoutSeconds(), step.datasource(), step.spool(),
                step.when() == null ? null : resolveBind(step.when(), use), step.enrich(),
                step.out(), null);
    }

    /** {@code binds.<name>} becomes what the reference wired to that name; anything else stands. */
    private static String resolveBind(String expr, FragmentUse use) {
        if (expr == null || !expr.startsWith("binds.")) {
            return expr;
        }
        String bind = expr.substring("binds.".length());
        String wired = use.params().get(bind);
        return wired == null ? expr : wired;
    }

    /**
     * A fragment's SQL sits beside the fragment document, and the compiler resolves a step's
     * {@code file:} against the <em>referencing</em> document's directory — so the expanded step
     * carries the path from there to here.
     */
    private String repath(String file, String fragmentName, Path source) {
        Path sqlFile = sources.getOrDefault(fragmentName, dir.resolve("x.yml")).getParent()
                .resolve(file).normalize();
        Path from = source.getParent();
        return from.relativize(sqlFile).toString().replace(java.io.File.separatorChar, '/');
    }
}
