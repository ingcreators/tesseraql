package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A source's hold (docs/caching.md decision 2): how long the rows its statement produced are
 * held before it runs again, and which tables the statement reads — what a writer's
 * {@code invalidates:} names to drop the hold. Both are required: an opt-in with a default is
 * a default, and a hold no write can reach is the defect the declaration exists to prevent
 * (the {@code file:} catalog's own rule, {@code TQL-FIELD-4621}). The word is the catalog's,
 * {@code cache:}, because it means the same thing there; the route-level {@code cache:} block
 * describes the response to the client and holds nothing.
 *
 * @param maxAge how long an entry serves before the statement runs again (a duration string
 *               such as {@code 30s} or {@code 5m}); positive
 * @param tables the tables the statement reads, as a writer would name them
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResultCacheSpec(String maxAge,
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> tables) {

    public ResultCacheSpec {
        tables = tables == null ? List.of() : List.copyOf(tables);
    }
}
