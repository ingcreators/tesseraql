package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.sql.LockBinding;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.LockSpec;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the framework-owned lock fields off the request and publishes the lock the command's
 * statement renders against (docs/edit-conflict.md decision 4).
 *
 * <p>{@code _lock} carries the value the user saw; {@code _overwrite} carries the deliberate
 * waiver of it. Both are reserved in {@link RequestBinder}, so the mass-assignment guard lets them
 * past and the input binder never sees them — which is why this step exists at all, and why it
 * owes the coercion the binder would have done.
 *
 * <p>It reads the parsed body rather than {@code request().param(…)}. The idempotency key's own
 * reader takes the parameter route, but a parameter view covers path, query and form and never a
 * JSON body; an API caller sends the lock in the body, so copying that placement would pass every
 * form case and fail every JSON one.
 */
public final class LockBinder implements Step {

    /** TQL-FIELD-2011: a locked route was reached with neither _lock nor _overwrite (HTTP 400). */
    private static final TqlErrorCode LOCK_REQUIRED = new TqlErrorCode(TqlDomain.FIELD, 2011);

    /** The waiver field — the conflict dialog's Overwrite button carries it as its submit value. */
    public static final String OVERWRITE_FIELD = "_overwrite";

    /** The exchange property carrying the resolved lock to the command's render. */
    public static final String LOCK_PROPERTY = "TqlLock";

    private final String routeId;
    private final String column;
    /** The declared type as a one-field shape the input coercion already understands, or null. */
    private final InputField declaredType;

    public LockBinder(String routeId, LockSpec lock) {
        this.routeId = routeId;
        this.column = lock.column();
        this.declaredType = lock.type() == null
                ? null
                : new InputField(lock.type(), false, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.of(),
                Map.class);
        Object rawBody = context.get("body");
        Map<String, Object> body = rawBody instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        // A waiver is presence, not a parsed boolean: the dialog's Overwrite button is a submit
        // button carrying value="1", and Boolean.parseBoolean("1") is false — which would drop
        // every overwrite silently. "false" and "0" are honoured so a caller can say no.
        Object waiver = body.get(OVERWRITE_FIELD);
        String waiverText = waiver == null ? null : String.valueOf(waiver).trim();
        boolean waived = waiverText != null && !waiverText.isEmpty()
                && !"false".equalsIgnoreCase(waiverText) && !"0".equals(waiverText);

        Object raw = body.get(LockBinding.PARAM);
        // The two arrive together on a real overwrite and that is not an ambiguity: htmx and the
        // native form both serialize the form's own fields alongside the submitter's name and
        // value, so the dialog's press sends the page's stale _lock beside the waiver.
        if (!waived && raw == null) {
            throw new TqlException(LOCK_REQUIRED, "Route '" + routeId + "' declares lock: " + column
                    + "; the request carried neither " + LockBinding.PARAM + " nor "
                    + OVERWRITE_FIELD);
        }

        Object value = waived ? null : coerced(exchange, raw);
        exchange.setProperty(LOCK_PROPERTY, new LockBinding(column, value, waived));
    }

    /**
     * The lock value, typed the way a declared input of the same type would be.
     *
     * <p>{@code String.valueOf} first is what makes a form's {@code "7"} and a JSON {@code 7}
     * normalize to the same bind against {@code type: integer} — the declared type decides, never
     * the class the value happened to arrive as. Without a declared type the value passes through
     * untouched, so a JSON caller keeps its own type and a form caller keeps a string; that
     * divergence is the price of an opaque lock, and it is why a numeric lock column declares one.
     *
     * <p>A malformed value is this step's refusal, not a field error: the coercion the binder
     * lends us reports against the field name it was given, and a violation naming {@code _lock}
     * would address a form control that does not exist. A non-scalar never reaches the coercion
     * at all — a duplicated form key arrives as a list, and a list is not a lock value.
     */
    private Object coerced(Exchange exchange, Object raw) {
        if (raw instanceof java.util.Collection || raw instanceof Map) {
            throw new TqlException(LOCK_REQUIRED, "Route '" + routeId + "': "
                    + LockBinding.PARAM + " must be a single value");
        }
        if (declaredType == null) {
            return raw;
        }
        String localeTag = exchange.getProperty(TesseraqlProperties.LOCALE, "en", String.class);
        try {
            return InputBinder.coerceScalar(LockBinding.PARAM, declaredType, String.valueOf(raw),
                    Locale.forLanguageTag(localeTag));
        } catch (TqlException ex) {
            throw new TqlException(LOCK_REQUIRED, "Route '" + routeId + "': " + LockBinding.PARAM
                    + " is not a valid " + declaredType.type() + " value");
        }
    }
}
