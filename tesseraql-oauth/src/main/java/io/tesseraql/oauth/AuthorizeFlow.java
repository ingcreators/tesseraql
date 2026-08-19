package io.tesseraql.oauth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.cxf.rs.security.oauth2.common.Client;
import org.apache.cxf.rs.security.oauth2.common.UserSubject;
import org.apache.cxf.rs.security.oauth2.grants.code.AuthorizationCodeRegistration;
import org.apache.cxf.rs.security.oauth2.grants.code.ServerAuthorizationCodeGrant;

/**
 * The authorize endpoint's decisions (docs/token-issuance.md decision 4): whether a request may
 * proceed, whether the consent screen is owed, and what the authorization response redirect
 * carries. One class behind three surfaces — the protocol GET, the consent page's model, and
 * the consent POST — so the validation ladder cannot drift between them.
 *
 * <p>The OAuth error split is deliberate: an unknown client or a redirect URI that does not
 * exactly match a registered one must never be redirected to, so those answer on the page;
 * everything else answers on the wire, as an error redirect to the validated callback. The
 * {@code resource} parameter is required — refusing with {@code invalid_target} rather than
 * guessing an audience is the fail-loud placeholder open question 2 keeps until the
 * measurement lands.
 */
public final class AuthorizeFlow {

    /** The outcome of a validation pass: exactly one of the three fields is set. */
    public record Outcome(String redirect, String pageError, Map<String, Object> consent) {
        static Outcome redirect(String url) {
            return new Outcome(url, null, null);
        }

        static Outcome pageError(String error) {
            return new Outcome(null, error, null);
        }

        static Outcome consent(Map<String, Object> model) {
            return new Outcome(null, null, model);
        }
    }

    private final OAuthStore store;
    private final TesseraqlOAuthDataProvider provider;
    private final Map<String, String> memberAddresses;
    private final String externalOrigin;
    private final Clock clock;

    public AuthorizeFlow(OAuthStore store, TesseraqlOAuthDataProvider provider,
            Map<String, String> memberAddresses, String externalOrigin, Clock clock) {
        this.store = store;
        this.provider = provider;
        this.memberAddresses = memberAddresses == null ? Map.of() : memberAddresses;
        this.externalOrigin = externalOrigin;
        this.clock = clock;
    }

    /**
     * The full ladder for one request, consent screen owed unless a recorded consent already
     * answers it. {@code subject}/{@code loginId} identify the session's principal;
     * {@code roleGrants} are its store-resolved grants (role, application, permissions).
     */
    public Outcome authorize(Map<String, String> query, String subject, String loginId,
            List<io.tesseraql.security.Principal.RoleGrant> roleGrants) {
        Validated validated = validate(query);
        if (validated.outcome != null) {
            return validated.outcome;
        }
        Optional<RecordedConsent> recorded = store.findConsent(
                validated.client.getClientId(), subject, validated.resource);
        if (recorded.isPresent()) {
            return Outcome.redirect(issueCode(validated, subject, loginId,
                    recorded.get().actingRole()));
        }
        return Outcome.consent(consentModel(validated, roleGrants));
    }

    /**
     * The consent page's model, re-validated from its own echoed parameters — the form's
     * hidden fields are the caller's to tamper with, and the POST validates again regardless.
     */
    public Outcome review(Map<String, String> query,
            List<io.tesseraql.security.Principal.RoleGrant> roleGrants) {
        Validated validated = validate(query);
        if (validated.outcome != null) {
            return validated.outcome;
        }
        return Outcome.consent(consentModel(validated, roleGrants));
    }

    /**
     * The consent decision: everything is validated afresh from the form, the consent is
     * recorded per client and per resource with the selected capacity riding it, and the
     * authorization response carries the single-use code, the echoed state, and RFC 9207's
     * {@code iss}.
     */
    public Outcome approve(Map<String, String> form, String subject, String loginId,
            List<io.tesseraql.security.Principal.RoleGrant> roleGrants) {
        Validated validated = validate(form);
        if (validated.outcome != null) {
            return validated.outcome;
        }
        if (!"approve".equals(form.get("decision"))) {
            return Outcome.redirect(errorRedirect(validated, "access_denied"));
        }
        List<io.tesseraql.security.Principal.RoleGrant> held =
                io.tesseraql.security.Activation.grantsFor(
                        principalWithGrants(roleGrants), validated.member);
        String actingRole = form.get("actingRole");
        if (actingRole != null && actingRole.isBlank()) {
            actingRole = null;
        }
        if (actingRole == null && held.size() == 1) {
            actingRole = held.get(0).role();
        }
        if (actingRole != null) {
            String selected = actingRole;
            if (held.stream().noneMatch(grant -> selected.equals(grant.role()))) {
                return Outcome.redirect(errorRedirect(validated, "access_denied"));
            }
        }
        store.saveConsent(new RecordedConsent(validated.client.getClientId(), subject,
                validated.resource, actingRole, clock.instant()));
        return Outcome.redirect(issueCode(validated, subject, loginId, actingRole));
    }

    private String issueCode(Validated validated, String subject, String loginId,
            String actingRole) {
        AuthorizationCodeRegistration registration = new AuthorizationCodeRegistration();
        registration.setClient(validated.client);
        registration.setRedirectUri(validated.redirectUri);
        registration.setAudience(validated.resource);
        registration.setClientCodeChallenge(validated.codeChallenge);
        registration.setClientCodeChallengeMethod("S256");
        UserSubject user = new UserSubject(loginId, subject);
        if (actingRole != null) {
            user.getProperties().put(TesseraqlOAuthDataProvider.ACTING_ROLE, actingRole);
        }
        registration.setSubject(user);
        ServerAuthorizationCodeGrant grant = provider.createCodeGrant(registration);
        StringBuilder url = new StringBuilder(validated.redirectUri)
                .append(validated.redirectUri.contains("?") ? '&' : '?')
                .append("code=").append(encode(grant.getCode()));
        if (validated.state != null) {
            url.append("&state=").append(encode(validated.state));
        }
        url.append("&iss=").append(encode(externalOrigin));
        return url.toString();
    }

    private Map<String, Object> consentModel(Validated validated,
            List<io.tesseraql.security.Principal.RoleGrant> roleGrants) {
        List<Map<String, String>> roles = new ArrayList<>();
        for (io.tesseraql.security.Principal.RoleGrant grant
                : io.tesseraql.security.Activation.grantsFor(
                        principalWithGrants(roleGrants), validated.member)) {
            roles.add(Map.of("role", grant.role()));
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("clientId", validated.client.getClientId());
        model.put("clientName", validated.client.getApplicationName() == null
                ? validated.client.getClientId()
                : validated.client.getApplicationName());
        model.put("member", validated.member);
        model.put("resource", validated.resource);
        model.put("redirectUri", validated.redirectUri);
        model.put("state", validated.state == null ? "" : validated.state);
        model.put("codeChallenge", validated.codeChallenge);
        model.put("roles", roles);
        model.put("singleRole", roles.size() == 1 ? roles.get(0).get("role") : "");
        return model;
    }

    /** The one validation ladder; a non-null {@code outcome} is the refusal. */
    private Validated validate(Map<String, String> params) {
        String clientId = params.get("client_id");
        Client client = clientId == null ? null : provider.getClient(clientId);
        if (client == null) {
            return Validated.refused(Outcome.pageError("unknown_client"));
        }
        String redirectUri = params.get("redirect_uri");
        // Exact match against the registered URIs — measured against Codex, which registers
        // the complete callback it will send (open question 2). Never redirected to on
        // failure: a mismatched callback is the one address that must not learn anything.
        if (redirectUri == null || !client.getRedirectUris().contains(redirectUri)) {
            return Validated.refused(Outcome.pageError("invalid_redirect_uri"));
        }
        Validated validated = new Validated();
        validated.client = client;
        validated.redirectUri = redirectUri;
        validated.state = params.get("state");
        if (params.get("response_type") != null
                && !"code".equals(params.get("response_type"))) {
            validated.outcome = Outcome.redirect(
                    errorRedirect(validated, "unsupported_response_type"));
            return validated;
        }
        validated.codeChallenge = params.get("code_challenge");
        String method = params.get("code_challenge_method");
        if (validated.codeChallenge == null || validated.codeChallenge.isBlank()
                || (method != null && !"S256".equals(method))) {
            validated.outcome = Outcome.redirect(errorRedirect(validated, "invalid_request"));
            return validated;
        }
        String resource = params.get("resource");
        validated.resource = resource;
        validated.member = memberFor(resource);
        if (validated.member == null) {
            validated.outcome = Outcome.redirect(errorRedirect(validated, "invalid_target"));
            return validated;
        }
        return validated;
    }

    /**
     * The member a resource identifier belongs to: its address, or anything under it — an MCP
     * surface's identifier lives below the member's address (stack-architecture.md decision 6).
     */
    private String memberFor(String resource) {
        if (resource == null || externalOrigin == null) {
            return null;
        }
        for (Map.Entry<String, String> member : memberAddresses.entrySet()) {
            String address = externalOrigin + member.getValue();
            if (resource.equals(address) || resource.startsWith(address + "/")) {
                return member.getKey();
            }
        }
        return null;
    }

    private String errorRedirect(Validated validated, String error) {
        StringBuilder url = new StringBuilder(validated.redirectUri)
                .append(validated.redirectUri.contains("?") ? '&' : '?')
                .append("error=").append(encode(error));
        if (validated.state != null) {
            url.append("&state=").append(encode(validated.state));
        }
        url.append("&iss=").append(encode(externalOrigin));
        return url.toString();
    }

    private static io.tesseraql.security.Principal principalWithGrants(
            List<io.tesseraql.security.Principal.RoleGrant> roleGrants) {
        return new io.tesseraql.security.Principal(null, null, null, null, List.of(),
                List.of(), List.of(), Map.of(),
                roleGrants == null ? List.of() : roleGrants, List.of());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static final class Validated {
        Client client;
        String redirectUri;
        String state;
        String codeChallenge;
        String resource;
        String member;
        Outcome outcome;

        static Validated refused(Outcome outcome) {
            Validated validated = new Validated();
            validated.outcome = outcome;
            return validated;
        }
    }
}
