package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.HttpMounts;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.compiler.pipeline.Pipeline;
import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.operations.app.AppInstaller;
import io.tesseraql.operations.app.AppUpgrader;
import io.tesseraql.security.Principal;
import io.tesseraql.security.jwt.JwtAuthenticator;
import io.tesseraql.security.policy.Atoms;
import io.tesseraql.security.policy.PolicyEngine;
import io.tesseraql.security.session.BrowserAuthenticator;
import io.tesseraql.security.session.CsrfValidator;
import io.tesseraql.security.session.SessionStore;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * The stack's authenticated deploy endpoint (docs/stack-shells.md, the deploy surface;
 * docs/runtime-replace.md open question 5's arrival): {@code POST /_tesseraql/deploy} on the
 * surface runtime receives a {@code .tqlapp} as its request body, checks the caller's
 * {@code tql.app.deploy.<name>} grant against the <b>package's declared name</b> — never a
 * request parameter, so a token scoped to one application cannot deploy another by renaming an
 * upload field — and writes the same intent files {@code tesseraql deploy} writes, through the
 * host's narrow pen. The reconciler watching the install root stays the one mechanism that moves
 * a runtime; a refused deploy answers as this endpoint's response and writes nothing.
 *
 * <p>Two callers, one endpoint: a pipeline presents a bearer from {@code tesseraql token}
 * (validated against the stack file's {@code security.jwt.*}, grafted onto this runtime's
 * configuration) with the package as the raw request body, and the ops deploy page's browser
 * session posts a multipart form — the file part carries the package, the CSRF token rides the
 * {@code X-CSRF-Token} header or the {@code _csrf} field, and the answer goes post/redirect/get
 * back to the page ({@code HX-Redirect} for the htmx submit) while API callers keep the JSON
 * contract. The authority is an operational guardrail, not isolation between distrusting teams
 * (docs/runtime-replace.md states the boundary): those get separate stacks.
 */
final class DeployRouteBuilder extends RouteBuilder {

    /** TQL-FIELD-2001: the deploy request carried no package bytes in its body. */
    private static final TqlErrorCode EMPTY_BODY = new TqlErrorCode(TqlDomain.FIELD, 2001);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HostContext.DeployPen pen;
    private final SessionStore sessions;

    DeployRouteBuilder(HostContext.DeployPen pen, SessionStore sessions) {
        this.pen = pen;
        this.sessions = sessions;
    }

    @Override
    public void configure() {
        Pipelines.Compilation pipelines = Pipelines.of(getContext())
                .compiling(java.util.List.of(
                        Pipeline.Handler.catching(TqlException.class, new ErrorResponseRenderer()),
                        Pipeline.Handler.catching(Exception.class, new ErrorResponseRenderer())));

        HttpMounts.mount(getContext(), "POST", "/_tesseraql/deploy", "system.deploy");
        pipelines.pipeline("system.deploy").process(this::deploy);
    }

    private void deploy(Exchange exchange) throws Exception {
        Principal principal = authenticate(exchange);
        // The package is the body, spooled to disk before anything reads it twice: peek and the
        // pen both want a file, and the transfer machinery's spool-first discipline applies —
        // an arbitrarily large upload never lives on the heap.
        Path tqlapp = spool(exchange);
        try {
            String name = new AppInstaller().peek(tqlapp).name();
            if (!Atoms.holds(principal.permissions(), Atoms.APP_DEPLOY_PREFIX, name)) {
                throw new TqlException(PolicyEngine.FORBIDDEN, "Principal is not granted "
                        + Atoms.APP_DEPLOY_PREFIX + name + ", which deploying the application"
                        + " this package declares requires (checked against the package, never"
                        + " a request parameter)");
            }
            boolean canary = Boolean.parseBoolean(query(exchange, "canary"));
            String weight = query(exchange, "weight");
            AppUpgrader.UpgradeResult result = pen.deploy(tqlapp, canary,
                    weight == null || weight.isBlank() ? null : Integer.parseInt(weight),
                    blankToNull(query(exchange, "sha256")));
            respond(exchange, result, canary);
        } finally {
            Files.deleteIfExists(tqlapp);
        }
    }

    /**
     * The caller's principal: a bearer when {@code Authorization} presents one, else the browser
     * session with its CSRF token — a state-changing POST, guarded like the token exchange it
     * mirrors. Either path throws the standard 401 when the credential does not resolve.
     */
    private Principal authenticate(Exchange exchange) throws Exception {
        String authorization = exchange.getMessage().getHeader("Authorization", String.class);
        if (authorization != null
                && authorization.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            JwtAuthenticator jwt = exchange.getContext().getRegistry().lookupByNameAndType(
                    TesseraqlProperties.JWT_AUTHENTICATOR_BEAN, JwtAuthenticator.class);
            if (jwt == null) {
                throw new TqlException(PolicyEngine.UNAUTHORIZED, "A bearer was presented but"
                        + " the stack validates none: declare security.jwt.* in"
                        + " tesseraql-stack.yml so the origin can verify tokens");
            }
            return jwt.authenticate(authorization);
        }
        String cookie = exchange.getMessage().getHeader("Cookie", String.class);
        Principal principal = new BrowserAuthenticator(sessions).authenticate(cookie);
        new CsrfValidator(sessions).validate(cookie, csrfToken(exchange));
        return principal;
    }

    /**
     * The CSRF token of a browser submit, on the compiled routes' two paths: the
     * {@code X-CSRF-Token} header (the {@code installCsrfHeader} htmx convention) or, for the
     * deploy page's no-JS plain form post, the hidden {@code _csrf} field — a multipart form
     * attribute platform-http mirrors into a header.
     */
    private static String csrfToken(Exchange exchange) {
        String header = exchange.getMessage().getHeader("X-CSRF-Token", String.class);
        return header != null ? header : exchange.getMessage().getHeader("_csrf", String.class);
    }

    /**
     * The answer, negotiated by caller (docs/stack-shells.md, the deploy page): the page's htmx
     * submit ({@code HX-Request}) gets {@code HX-Redirect} and its no-JS degradation (a form
     * post accepting HTML) a 303 — both post/redirect/get back to the deploy page with the
     * result riding query parameters — while API callers (the CLI's {@code deploy --url},
     * pipelines) keep the JSON contract unchanged.
     */
    private static void respond(Exchange exchange, AppUpgrader.UpgradeResult result,
            boolean canary) throws Exception {
        boolean htmx = "true".equals(exchange.getMessage().getHeader("HX-Request", String.class));
        String accept = exchange.getMessage().getHeader("Accept", String.class);
        // Inbound form fields surfaced as headers must not echo back onto the response.
        exchange.getMessage().removeHeaders("*");
        if (htmx || (accept != null && accept.contains("text/html"))) {
            String target = io.tesseraql.camel.BasePath.url(exchange,
                    "/_tesseraql/ops/console/deploy?deployed=" + encode(result.appName())
                            + "&fromVersion=" + encode(result.fromVersion())
                            + "&toVersion=" + encode(result.toVersion())
                            + (canary ? "&canary=true" : ""));
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "text/plain; charset=utf-8");
            exchange.getMessage().setBody("");
            if (htmx) {
                // htmx surfaces a redirect status to the XHR, not the tab; HX-Redirect on a 200
                // is its full-navigation signal.
                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
                exchange.getMessage().setHeader("HX-Redirect", target);
            } else {
                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 303);
                exchange.getMessage().setHeader("Location", target);
            }
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", result.appName());
        body.put("fromVersion", result.fromVersion());
        body.put("toVersion", result.toVersion());
        body.put("canary", canary);
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE,
                "application/json; charset=utf-8");
        exchange.getMessage().setBody(MAPPER.writeValueAsString(body));
    }

    /**
     * The uploaded package bytes: for the deploy page's multipart form the file part — a part
     * named {@code file} preferred, first file part as fallback, the {@code FileImportProcessor}
     * precedent — otherwise the raw request body ({@code deploy --url}, pipelines). A multipart
     * request without a file part is refused as an empty deploy rather than spooling the
     * envelope bytes as if they were a package.
     */
    private static InputStream packageStream(Exchange exchange) throws Exception {
        String contentType = exchange.getMessage().getHeader(Exchange.CONTENT_TYPE, String.class);
        if (contentType != null
                && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/")) {
            org.apache.camel.attachment.AttachmentMessage attachments = exchange
                    .getMessage(org.apache.camel.attachment.AttachmentMessage.class);
            jakarta.activation.DataHandler part = null;
            if (attachments != null && attachments.hasAttachments()) {
                part = attachments.getAttachment("file") != null
                        ? attachments.getAttachment("file")
                        : attachments.getAttachments().values().iterator().next();
            }
            if (part == null) {
                throw new TqlException(EMPTY_BODY, "The multipart deploy request carried no"
                        + " file part with the .tqlapp package bytes");
            }
            return part.getInputStream();
        }
        return exchange.getMessage().getBody(InputStream.class);
    }

    /** The uploaded package, spooled off-heap; the caller deletes it when done. */
    private static Path spool(Exchange exchange) throws Exception {
        InputStream body = packageStream(exchange);
        Path spooled = Files.createTempFile("tesseraql-deploy", ".tqlapp");
        try {
            long bytes = body == null
                    ? 0
                    : Files.copy(body,
                            spooled, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (bytes == 0) {
                throw new TqlException(EMPTY_BODY, "The request body must be the .tqlapp"
                        + " package bytes to deploy");
            }
            return spooled;
        } catch (Exception failed) {
            Files.deleteIfExists(spooled);
            throw failed;
        }
    }

    private static String query(Exchange exchange, String name) {
        Object value = exchange.getMessage().getHeader(name);
        return value == null ? null : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value,
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
