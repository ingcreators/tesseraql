package io.tesseraql.compiler.binding;

import io.tesseraql.pipeline.BasePath;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.yaml.i18n.I18nSettings;
import java.util.Map;

/**
 * The re-login dialog of the kit's session-expiry recipe (docs/hypermedia-ui.md "Session
 * expiry"): the fragment an htmx request receives when its session is gone, aimed at the
 * shell's shared {@code data-hc-session-expiry} host via {@code HX-Retarget}. The kit does the
 * rest — {@code installRemoteDialog} opens it, and {@code installSessionExpiry} replays the
 * interrupted request once the login response fires {@code hc:sessionrenewed}.
 *
 * <p>Shared between the 401 branch ({@link ErrorResponseRenderer}) and the login endpoint's
 * htmx legs (the 422 invalid-credentials and 429 throttled re-renders), so the three responses
 * cannot drift apart. The offered methods mirror the login page's own model (the
 * {@code tesseraqlLoginMethods} bean): a password form posts back to the login endpoint in
 * place, while an SSO method is a full-page link — a provider round trip cannot happen inside
 * a dialog, so that leg forfeits the replay and says so by navigating.
 */
public final class SessionExpiredDialog {

    /** The marker the page-level beforeSwap allowance gates the 401 swap on. */
    public static final String MARKER = "data-tql-session-expired";

    private SessionExpiredDialog() {
    }

    /**
     * Renders the dialog.
     *
     * @param methods   the login-method model ({@code password}/{@code sso} flags, method URLs)
     * @param alertHtml an error alert to open the dialog with (a failed re-login attempt), or
     *                  null on the initial 401
     */
    public static String render(Exchange exchange, I18nSettings i18n, String tag,
            Map<String, Object> methods, String alertHtml) {
        String loginUrl = BasePath.url(exchange, "/_tesseraql/login");
        boolean password = Boolean.TRUE.equals(methods.get("password"));
        StringBuilder html = new StringBuilder();
        html.append("<dialog class=\"hc-dialog\" ").append(MARKER)
                .append(" aria-labelledby=\"tql-relogin-title\">");
        html.append("<h2 class=\"hc-dialog__title\" id=\"tql-relogin-title\">")
                .append(text(i18n, tag, "tql.session.expiredTitle")).append("</h2>");
        if (alertHtml != null) {
            html.append(alertHtml);
        }
        html.append("<p>").append(text(i18n, tag, "tql.session.expiredBody")).append("</p>");
        if (password) {
            // The same fields as the login page (loginId/password/otp), posting to the same
            // endpoint; hx-swap "none" because the answer is headers — HX-Trigger on success,
            // a retargeted re-render of this dialog on failure.
            html.append("<form class=\"hc-stack\" hx-post=\"").append(escape(loginUrl))
                    .append("\" hx-target=\"this\" hx-swap=\"none\">");
            field(html, i18n, tag, "tql.session.login", "tql-relogin-login",
                    "<input class=\"hc-input\" id=\"tql-relogin-login\" name=\"loginId\""
                            + " autocomplete=\"username\" required autofocus>");
            field(html, i18n, tag, "tql.session.password", "tql-relogin-password",
                    "<input class=\"hc-input\" id=\"tql-relogin-password\" name=\"password\""
                            + " type=\"password\" autocomplete=\"current-password\" required>");
            field(html, i18n, tag, "tql.session.otp", "tql-relogin-otp",
                    "<input class=\"hc-input\" id=\"tql-relogin-otp\" name=\"otp\""
                            + " pattern=\"[0-9]{6}|[a-z0-9]{4}-?[a-z0-9]{4}\" maxlength=\"9\""
                            + " autocomplete=\"one-time-code\">");
            html.append("<button class=\"hc-button\" data-variant=\"primary\""
                    + " type=\"submit\">")
                    .append(text(i18n, tag, "tql.session.signIn")).append("</button>");
            html.append("</form>");
        }
        ssoLink(html, exchange, i18n, tag, methods.get("oidc"), "tql.session.oidc");
        ssoLink(html, exchange, i18n, tag, methods.get("saml"), "tql.session.saml");
        // Cancel lives in its own <form method="dialog"> (forms cannot nest): declarative
        // close, no inline JS. The interrupted request stays remembered — a later successful
        // sign-in elsewhere on the page will not replay it, because the kit replays only on
        // hc:sessionrenewed, which only the dialog's own success leg fires.
        html.append("<form method=\"dialog\">"
                + "<button class=\"hc-button\" data-variant=\"ghost\">")
                .append(text(i18n, tag, "tql.session.cancel")).append("</button></form>");
        return html.append("</dialog>").toString();
    }

    /** The localized error alert a failed re-login re-opens the dialog with. */
    public static String alert(I18nSettings i18n, String tag, String messageKey) {
        return "<div class=\"hc-alert\" data-variant=\"error\" role=\"alert\""
                + " data-hc-field-errors><p class=\"hc-alert__title\">"
                + text(i18n, tag, messageKey) + "</p></div>";
    }

    private static void field(StringBuilder html, I18nSettings i18n, String tag,
            String labelKey, String id, String input) {
        html.append("<div class=\"hc-field\"><label class=\"hc-field__label\" for=\"")
                .append(id).append("\">").append(text(i18n, tag, labelKey))
                .append("</label>").append(input).append("</div>");
    }

    /** An enabled SSO method renders as a full-page link (see the class comment). */
    private static void ssoLink(StringBuilder html, Exchange exchange, I18nSettings i18n,
            String tag, Object method, String labelKey) {
        if (!(method instanceof Map<?, ?> model)
                || !Boolean.TRUE.equals(model.get("enabled"))) {
            return;
        }
        html.append("<a class=\"hc-button\" data-variant=\"secondary\" href=\"")
                .append(escape(BasePath.url(exchange, String.valueOf(model.get("url")))))
                .append("\">").append(text(i18n, tag, labelKey)).append("</a>");
    }

    private static String text(I18nSettings i18n, String tag, String key) {
        String resolved = i18n.catalog().resolve(tag, i18n.defaultTag(), key);
        return escape(resolved != null ? resolved : key);
    }

    private static String escape(String value) {
        return io.tesseraql.core.text.Escapes.html(value);
    }
}
