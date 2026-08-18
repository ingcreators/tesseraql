package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.binding.ErrorResponseRenderer;
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
 * configuration), and a browser session presents its cookie with the CSRF token — the ops deploy
 * page's shape when it lands. The authority is an operational guardrail, not isolation between
 * distrusting teams (docs/runtime-replace.md states the boundary): those get separate stacks.
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
        onException(TqlException.class).handled(true).process(new ErrorResponseRenderer());
        onException(Exception.class).handled(true).process(new ErrorResponseRenderer());

        rest().post("/_tesseraql/deploy").to("direct:tql.deploy");
        from("direct:tql.deploy").routeId("system.deploy").process(this::deploy);
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
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", result.appName());
            body.put("fromVersion", result.fromVersion());
            body.put("toVersion", result.toVersion());
            body.put("canary", canary);
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE,
                    "application/json; charset=utf-8");
            exchange.getMessage().setBody(MAPPER.writeValueAsString(body));
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
        new CsrfValidator(sessions).validate(cookie,
                exchange.getMessage().getHeader("X-CSRF-Token", String.class));
        return principal;
    }

    /** The uploaded package, spooled off-heap; the caller deletes it when done. */
    private static Path spool(Exchange exchange) throws Exception {
        InputStream body = exchange.getMessage().getBody(InputStream.class);
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
}
