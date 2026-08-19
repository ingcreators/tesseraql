package io.tesseraql.oauth;

import java.time.Instant;

/**
 * A subject's recorded consent, per client and per resource (docs/token-issuance.md decision 4;
 * stack-architecture.md decision 10): consenting to one application in a stack is not consenting
 * to the rest. The acting role a concurrent-role user selected rides the consent so a refreshed
 * connection keeps its capacity; changing it is a re-authorization.
 */
public record RecordedConsent(
        String clientId,
        String subject,
        String resourceId,
        String actingRole,
        Instant grantedAt) {
}
