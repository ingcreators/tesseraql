package io.tesseraql.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.cxf.rs.security.oauth2.grants.code.ServerAuthorizationCodeGrant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The authorize endpoint's validation ladder (docs/token-issuance.md decision 4): what answers
 * on the page, what answers on the wire, when the consent screen is owed, and what the
 * authorization response carries. One ladder behind three surfaces, so every case here holds
 * for the protocol GET, the page model, and the consent POST alike.
 */
class AuthorizeFlowTest {

    private static final String ORIGIN = "https://stack.example.com";
    private static final String REDIRECT = "http://127.0.0.1:49681/callback/gSuWNlcOrmWI";
    private static final String RESOURCE = ORIGIN + "/shop";

    private final InMemoryOAuthStore store = new InMemoryOAuthStore();
    private final CapturingSigner signer = new CapturingSigner();
    private final MutableClock clock = new MutableClock();
    private final TesseraqlOAuthDataProvider provider = new TesseraqlOAuthDataProvider(store,
            signer, clock);
    private final AuthorizeFlow flow = new AuthorizeFlow(store, provider,
            Map.of("shop", "/shop"), ORIGIN, clock);

    private final List<Principal.RoleGrant> grants = List.of(
            new Principal.RoleGrant("staff", null, List.of("read")),
            new Principal.RoleGrant("approver", "shop", List.of("shop.approve")));

    @BeforeEach
    void registerClient() {
        store.saveClient(new RegisteredClient("codex", null, List.of(REDIRECT), "Codex CLI",
                null, clock.instant(), null));
    }

    @Test
    void anUnknownClientAnswersOnThePageAndNeverRedirects() {
        AuthorizeFlow.Outcome outcome = flow.authorize(query(q -> q.put("client_id", "nope")),
                "u-1", "eve", grants);

        assertThat(outcome.pageError()).isEqualTo("unknown_client");
        assertThat(outcome.redirect()).isNull();
    }

    @Test
    void aMismatchedRedirectUriAnswersOnThePageAndNeverRedirects() {
        AuthorizeFlow.Outcome outcome = flow.authorize(
                query(q -> q.put("redirect_uri", "http://127.0.0.1:50000/elsewhere")),
                "u-1", "eve", grants);

        assertThat(outcome.pageError()).isEqualTo("invalid_redirect_uri");
        assertThat(outcome.redirect()).isNull();
    }

    @Test
    void aMissingChallengeIsRefusedOnTheWire() {
        AuthorizeFlow.Outcome outcome = flow.authorize(
                query(q -> q.remove("code_challenge")), "u-1", "eve", grants);

        assertThat(outcome.redirect()).startsWith(REDIRECT)
                .contains("error=invalid_request").contains("state=xyz");
    }

    @Test
    void thePlainMethodIsRefusedOnTheWire() {
        AuthorizeFlow.Outcome outcome = flow.authorize(
                query(q -> q.put("code_challenge_method", "plain")), "u-1", "eve", grants);

        assertThat(outcome.redirect()).contains("error=invalid_request");
    }

    @Test
    void aMissingOrForeignResourceIsInvalidTarget() {
        assertThat(flow.authorize(query(q -> q.remove("resource")), "u-1", "eve", grants)
                .redirect()).contains("error=invalid_target");
        assertThat(flow.authorize(query(q -> q.put("resource", "https://elsewhere.example")),
                "u-1", "eve", grants).redirect()).contains("error=invalid_target");
    }

    @Test
    void anMcpResourceUnderTheMembersAddressResolvesToTheMember() {
        AuthorizeFlow.Outcome outcome = flow.authorize(
                query(q -> q.put("resource", RESOURCE + "/mcp")), "u-1", "eve", grants);

        assertThat(outcome.consent()).containsEntry("member", "shop");
    }

    @Test
    void theFirstAuthorizeOwesTheConsentScreen() {
        AuthorizeFlow.Outcome outcome = flow.authorize(query(q -> {
        }), "u-1", "eve", grants);

        assertThat(outcome.consent())
                .containsEntry("clientName", "Codex CLI")
                .containsEntry("member", "shop")
                .containsEntry("singleRole", "approver");
    }

    @Test
    void approvalRecordsConsentAndTheResponseCarriesCodeStateAndIssuer() {
        AuthorizeFlow.Outcome outcome = flow.approve(form("approve", null), "u-1", "eve",
                grants);

        String redirect = outcome.redirect();
        assertThat(redirect).startsWith(REDIRECT + "?")
                .contains("code=").contains("state=xyz")
                .contains("iss=" + java.net.URLEncoder.encode(ORIGIN,
                        java.nio.charset.StandardCharsets.UTF_8));

        // Consent is per client and per resource, with the single held role auto-selected.
        assertThat(store.findConsent("codex", "u-1", RESOURCE))
                .hasValueSatisfying(
                        consent -> assertThat(consent.actingRole()).isEqualTo("approver"));

        // The code is real: it redeems into a grant carrying the resource and the capacity.
        String code = redirect.replaceAll(".*[?&]code=([^&]+).*", "$1");
        ServerAuthorizationCodeGrant grant = provider.removeCodeGrant(code);
        assertThat(grant).isNotNull();
        assertThat(grant.getExtraProperties())
                .containsEntry(TesseraqlOAuthDataProvider.RESOURCE, RESOURCE);
        assertThat(grant.getSubject().getProperties())
                .containsEntry(TesseraqlOAuthDataProvider.ACTING_ROLE, "approver");
    }

    @Test
    void aRecordedConsentSkipsTheScreen() {
        flow.approve(form("approve", null), "u-1", "eve", grants);

        AuthorizeFlow.Outcome again = flow.authorize(query(q -> {
        }), "u-1", "eve", grants);

        assertThat(again.consent()).isNull();
        assertThat(again.redirect()).contains("code=");
    }

    @Test
    void denialAnswersAccessDeniedOnTheWire() {
        AuthorizeFlow.Outcome outcome = flow.approve(form("deny", null), "u-1", "eve", grants);

        assertThat(outcome.redirect()).contains("error=access_denied");
        assertThat(store.findConsent("codex", "u-1", RESOURCE)).isEmpty();
    }

    @Test
    void anUnheldActingRoleIsDeniedNotWidened() {
        AuthorizeFlow.Outcome outcome = flow.approve(form("approve", "auditor"), "u-1", "eve",
                grants);

        assertThat(outcome.redirect()).contains("error=access_denied");
        assertThat(store.findConsent("codex", "u-1", RESOURCE)).isEmpty();
    }

    private static Map<String, String> query(
            java.util.function.Consumer<Map<String, String>> mutate) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("client_id", "codex");
        query.put("redirect_uri", REDIRECT);
        query.put("response_type", "code");
        query.put("state", "xyz");
        query.put("code_challenge", "a-challenge-of-plausible-length-0123456789abcdef");
        query.put("code_challenge_method", "S256");
        query.put("resource", RESOURCE);
        mutate.accept(query);
        return query;
    }

    private static Map<String, String> form(String decision, String actingRole) {
        Map<String, String> form = query(q -> {
        });
        form.put("decision", decision);
        if (actingRole != null) {
            form.put("actingRole", actingRole);
        }
        return form;
    }
}
