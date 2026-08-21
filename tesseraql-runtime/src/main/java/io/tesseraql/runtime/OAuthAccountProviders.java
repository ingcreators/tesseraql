package io.tesseraql.runtime;

import io.tesseraql.core.service.ServiceProviders;
import io.tesseraql.oauth.OAuthRuntimeExtension;
import io.tesseraql.oauth.OAuthStore;
import io.tesseraql.oauth.RecordedConsent;
import io.tesseraql.oauth.RegisteredClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The account surface's "applications you have authorised" providers (docs/token-issuance.md
 * decision 4): what a subject granted, per client and per resource, and the revocation that
 * deletes the consent and its refresh chains together — so the connection is gone, not merely
 * dormant until re-consent.
 *
 * <p>Registered unconditionally and answering {@code enabled: false} wherever the oauth
 * extension bound no store — the account app also mounts on unhosted runtimes, and its page
 * must render the honest state there rather than meet a missing provider.
 */
final class OAuthAccountProviders {

    private OAuthAccountProviders() {
    }

    static void register(ServiceProviders services, Function<String, Object> beans) {
        services.register("oauth.account.connections", params -> {
            OAuthStore store = (OAuthStore) beans.apply(OAuthRuntimeExtension.STORE_BEAN);
            if (store == null) {
                return Map.of("enabled", false, "connections", List.of());
            }
            @SuppressWarnings("unchecked")
            Map<String, String> addresses = (Map<String, String>) beans
                    .apply(OAuthRuntimeExtension.MEMBER_ADDRESSES_BEAN);
            String subject = String.valueOf(params.get("subject"));
            List<Map<String, Object>> connections = new ArrayList<>();
            for (RecordedConsent consent : store.consentsFor(subject)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("clientId", consent.clientId());
                row.put("clientName", store.findClient(consent.clientId())
                        .map(RegisteredClient::clientName)
                        .filter(name -> name != null && !name.isBlank())
                        .orElse(consent.clientId()));
                row.put("resource", consent.resourceId());
                row.put("member", memberOf(consent.resourceId(), addresses));
                row.put("actingRole", consent.actingRole() == null ? "" : consent.actingRole());
                row.put("grantedAt", String.valueOf(consent.grantedAt()));
                row.put("liveTokens", store.refreshTokensFor(consent.clientId(), subject)
                        .stream()
                        .filter(token -> consent.resourceId().equals(token.resourceId()))
                        .count());
                connections.add(row);
            }
            return Map.of("enabled", true, "connections", connections);
        });

        services.register("oauth.account.revoke", params -> {
            OAuthStore store = (OAuthStore) beans.apply(OAuthRuntimeExtension.STORE_BEAN);
            if (store == null) {
                return Map.of("enabled", false);
            }
            String subject = String.valueOf(params.get("subject"));
            String clientId = String.valueOf(params.get("clientId"));
            String resource = String.valueOf(params.get("resource"));
            Instant now = Instant.now();
            // The consent and the refresh chains go together (decision 4): a revoked
            // connection cannot refresh, and coming back is a re-authorization.
            store.deleteConsent(clientId, subject, resource);
            store.refreshTokensFor(clientId, subject).stream()
                    .filter(token -> resource.equals(token.resourceId()))
                    .forEach(token -> store.revokeChain(token.chainId(), now));
            return Map.of("enabled", true, "revoked", true);
        });
    }

    /** The member behind a resource, for the page's label — or blank outside the stack. */
    private static String memberOf(String resource, Map<String, String> addresses) {
        if (addresses == null || resource == null) {
            return "";
        }
        for (Map.Entry<String, String> member : addresses.entrySet()) {
            if (resource.endsWith(member.getValue())
                    || resource.contains(member.getValue() + "/")) {
                return member.getKey();
            }
        }
        return "";
    }
}
