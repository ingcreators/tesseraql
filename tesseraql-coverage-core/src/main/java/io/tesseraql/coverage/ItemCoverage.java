package io.tesseraql.coverage;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * Coverage of a discrete set of items — a reusable model for non-SQL coverage kinds such as
 * assertion, IAM-contract, route, security, SAML, and SCIM coverage (design ch. 14). Each kind
 * declares the items it could cover and records the ones a test run actually exercised; the ratio is
 * covered-of-declared.
 */
public final class ItemCoverage {

    private final String kind;
    private final Set<String> declared = new TreeSet<>();
    private final Set<String> covered = new TreeSet<>();

    public ItemCoverage(String kind) {
        this.kind = kind;
    }

    public String kind() {
        return kind;
    }

    /** Declares an item that could be covered. */
    public ItemCoverage declare(String item) {
        if (item != null && !item.isBlank()) {
            declared.add(item);
        }
        return this;
    }

    /** Declares every item that could be covered. */
    public ItemCoverage declareAll(Collection<String> items) {
        items.forEach(this::declare);
        return this;
    }

    /** Records that an item was exercised. */
    public ItemCoverage cover(String item) {
        if (item != null && !item.isBlank()) {
            covered.add(item);
        }
        return this;
    }

    public Set<String> declared() {
        return Set.copyOf(declared);
    }

    public Set<String> covered() {
        return Set.copyOf(covered);
    }

    /** Declared items that were never covered. */
    public Set<String> uncovered() {
        Set<String> remaining = new TreeSet<>(declared);
        remaining.removeAll(covered);
        return remaining;
    }

    /** Covered-of-declared ratio in {@code [0,1]}; 1.0 when nothing is declared. */
    public double ratio() {
        if (declared.isEmpty()) {
            return 1.0;
        }
        long hit = declared.stream().filter(covered::contains).count();
        return (double) hit / declared.size();
    }
}
