package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.rows.RowTokens;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decodes the selection a grid page posts (docs/list-surface.md decision 9): each repeated
 * {@code ids} form field arrives as an opaque row token, and for a single-column key the token
 * decodes to the bare canonical value before binding — so the route's declared {@code ids}
 * array input receives what a hand-posted id would be. Composite tokens pass through
 * unchanged: the snapshot work consumes tokens natively, and a composite bulk route owns its
 * own decoding for now (the recorded slice-8 cut).
 *
 * <p>Wired by the route compiler onto every POST route some list view's {@code actions:}
 * target, keyed by that view's declared {@code key:}. Tokens prove nothing — the route's own
 * security and SQL decide what the ids may touch.
 */
public final class BulkSelectionDecoder implements Step {

    private static final TqlErrorCode VALIDATION = new TqlErrorCode(TqlDomain.FIELD, 2001);

    private static final Step NONE = exchange -> {
    };

    private final List<String> key;

    private BulkSelectionDecoder(List<String> key) {
        this.key = List.copyOf(key);
    }

    /** The decoder for a route some view's actions target, or a no-op for every other route. */
    public static Step forRoute(List<String> key) {
        return key == null || key.isEmpty() ? NONE : new BulkSelectionDecoder(key);
    }

    @Override
    public void process(Exchange exchange) {
        List<String> raw = exchange.request().formFields().get("ids");
        if (raw == null || raw.isEmpty() || key.size() != 1) {
            return;
        }
        List<String> decoded = new ArrayList<>(raw.size());
        for (String token : raw) {
            try {
                decoded.add(RowTokens.decode(token, key).get(0));
            } catch (IllegalArgumentException ex) {
                throw TqlException.builder(VALIDATION)
                        .message("Invalid ids selection token")
                        .details(Map.of("fields", List.of(Map.of(
                                "field", "ids", "code", "ids", "message", "tql.input.ids"))))
                        .build();
            }
        }
        exchange.request().formFields().put("ids", decoded);
    }
}
