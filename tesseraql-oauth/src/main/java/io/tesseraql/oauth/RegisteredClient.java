package io.tesseraql.oauth;

import java.time.Instant;
import java.util.List;

/**
 * A dynamically registered OAuth client (docs/token-issuance.md decision 5): what it sent, what
 * it was issued, and when it was last used. Nothing about a client is trusted beyond its
 * redirect URIs, which are matched exactly, and its credentials, which are verified against the
 * stored hash. {@code clientName} and {@code metadataJson} are display text chosen by the party
 * asking to be authorised — rendered escaped, never as though the framework vouched for it.
 *
 * @param secretHash SHA-256 hex of the issued secret, or {@code null} for a public client
 * @param lastSeenAt updated when the client authenticates, so an operator can find
 *                   registrations nothing ever used — registration churn is by design
 *                   (a client re-registers whenever its ephemeral callback port changes)
 */
public record RegisteredClient(
        String clientId,
        String secretHash,
        List<String> redirectUris,
        String clientName,
        String metadataJson,
        Instant registeredAt,
        Instant lastSeenAt) {

    public RegisteredClient {
        redirectUris = List.copyOf(redirectUris);
    }
}
