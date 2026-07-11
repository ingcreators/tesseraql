# SAML sign-in

SAML logs a browser user in through a corporate identity provider (Okta, ADFS, Entra ID,
Keycloak, …) using the SAML 2.0 **SP-initiated web SSO** flow with the HTTP-POST binding, then
issues a TesseraQL browser session — the same session cookie (`tesseraql_sid`) that password and
OIDC login produce, so every `auth: browser` route is satisfied identically however the user signed
in (see [authentication.md](authentication.md#browser-sessions-and-the-admin-console)). Once
enabled, the login page shows **Sign in with SAML**, linking to `GET /_tesseraql/saml/login`; the
page the user originally requested rides along as RelayState and is always sanitized to a
same-origin path after login (no open redirect).

Like OIDC, SAML is an opt-in leaf module: add the `tesseraql-saml` jar to the runtime classpath
(or drop a signed jar in the app's plugin directory) and set `tesseraql.saml.enabled: true` — the
SP routes install themselves. All SAML processing is JDK-only; there is no third-party SAML or XML
security dependency.

## Configuration

A complete SP configuration:

```yaml
tesseraql:
  saml:
    enabled: true
    sp:
      audience: https://app.example.com/saml           # SP entity id (required)
      acsUrl: https://app.example.com/_tesseraql/saml/acs
      nameIdFormat: urn:oasis:names:tc:SAML:2.0:nameid-format:emailAddress
      signingKey: saml/sp-key.pem                      # optional; signs redirect messages
    idp:
      metadata: saml/idp-metadata.xml                  # pinned signing key source (or publicKey)
      ssoUrl: https://idp.example.com/sso              # IdP single sign-on URL
      sloUrl: https://idp.example.com/slo              # IdP single logout URL (optional)
    attributes:
      loginId: uid
      displayName: displayName
      email: mail
      roles: roles
      groups: memberOf
      tenant: tenantId
    link:
      enabled: true       # resolve/authorize via local identity contracts (else IdP-asserted)
      provision: false    # JIT-provision an unknown user the first time they sign in
    allowIdpInitiated: false     # accept unsolicited (IdP-initiated) responses
    requireSignedLogout: true    # inbound single-logout requests must be signed
```

| Key | Required | Meaning |
| --- | --- | --- |
| `sp.audience` | yes | The SP entity id. Every assertion's audience restriction must include it. |
| `sp.acsUrl` | recommended | The public URL of the Assertion Consumer Service (`…/_tesseraql/saml/acs`). Enables the recipient check, the **Sign in with SAML** login route, and the SP metadata endpoint; without it those are off. |
| `sp.nameIdFormat` | no | The NameID format advertised in SP metadata (default `unspecified`). |
| `sp.signingKey` | no | Path to an RSA private key (PKCS#8, PEM or DER) used to sign outgoing HTTP-Redirect messages. Without it, redirects are sent unsigned. |
| `idp.metadata` | one of these two | Path to the IdP's SAML metadata XML; the signing certificate is extracted from its `IDPSSODescriptor` (a `use="signing"` key, else one with no `use`). |
| `idp.publicKey` | one of these two | Path to the IdP signing key directly: an X.509 certificate (PEM or DER) or a bare public key (PEM `PUBLIC KEY` or DER). |
| `idp.ssoUrl` | for SP-initiated login | Where `GET /_tesseraql/saml/login` redirects the browser with its AuthnRequest. |
| `idp.sloUrl` | for single logout | Where logout sends the LogoutRequest; also enables the inbound single-logout endpoint. |
| `attributes.*` | no | Assertion-attribute names mapped onto the principal (see below). |
| `link.enabled` | no | Resolve the principal from the local identity store instead of the assertion (default `false`). |
| `link.provision` | no | With linking on, create a local user on first federated sign-in (default `false`). |
| `allowIdpInitiated` | no | Accept responses that answer no pending request (default `false`). |
| `requireSignedLogout` | no | Reject unsigned inbound logout requests (default `true`). |

Key and metadata paths are files **relative to the app home**, read at startup. Fetching IdP
metadata from a URL is not currently supported — download it and ship the file with the app.
A Studio wizard (**SAML SP**, under the IAM admin wizards) generates this config block.

## Metadata exchange

When `sp.acsUrl` is set, the SP publishes its own metadata at `GET /_tesseraql/saml/metadata`
(`application/samlmetadata+xml`, unauthenticated): an `EntityDescriptor` advertising the entity id,
the HTTP-POST Assertion Consumer Service, and the NameID format, declaring
`WantAssertionsSigned="true"`. Point the IdP at that URL, or register the two values manually:

- **Entity id / audience** — `tesseraql.saml.sp.audience`
- **ACS URL (HTTP-POST)** — `https://<your-app>/_tesseraql/saml/acs`

In the other direction, download the IdP's metadata XML into the app (`idp.metadata`) — or, if the
IdP hands you only a certificate, use `idp.publicKey` — and copy its SSO/SLO URLs into
`idp.ssoUrl` / `idp.sloUrl`.

## Attribute mapping and user linking

The assertion's subject **NameID** becomes the principal subject, and — unless
`attributes.loginId` names an attribute — also the login id. Each `attributes.*` entry names the
SAML `Attribute` whose value fills the corresponding principal field: display name, email, tenant,
and the (possibly multi-valued) roles and groups. Unmapped fields stay empty; the full attribute
set is available as principal claims either way.

Where roles come from is the `link` switch:

- **Linking off (default):** the principal is built from the assertion — IdP-asserted roles and
  groups drive authorization directly.
- **Linking on:** the login id is resolved against the local identity store, so authorization uses
  locally managed roles and permissions rather than whatever the IdP asserts. A federated user
  with no local account is rejected — unless `link.provision: true`, which creates an ACTIVE local
  user (login id, display name, email, tenant) on first sign-in. With linking on, the `saml`
  coverage kind tracks that the identity contracts the login path runs are exercised by contract
  test cases (see [testing.md](testing.md#coverage-kinds)).

## Logout

`GET /_tesseraql/saml/logout` invalidates the local session and clears the cookie; when
`idp.sloUrl` is configured it then redirects to the IdP with a LogoutRequest carrying the
federated NameID and session index, so the IdP can end its own session too. When the IdP initiates
logout, it calls `GET /_tesseraql/saml/slo` (available when `idp.sloUrl` is set): the request's
redirect signature is verified against the pinned IdP key (mandatory unless
`requireSignedLogout: false`), the local session ends, and a LogoutResponse redirect answers the
IdP.

## Security posture

- **Pinned IdP key.** Signatures verify only against the configured key; any `KeyInfo` embedded in
  the message is ignored, so an attacker cannot present a self-chosen certificate.
- **Response validation.** XML parsing disallows DTDs and external entities; the response status
  must be Success; signature verification runs with JDK secure validation (weak algorithms
  rejected, transforms restricted); and the consumed assertion must lie inside the signed element —
  an assertion smuggled outside the signed subtree (XML signature wrapping) is never trusted. The
  audience restriction must include `sp.audience`, and when `sp.acsUrl` is set the subject
  confirmation's recipient must match it.
- **Replay protection.** A database-backed guard (tables `tql_saml_request` and
  `tql_saml_seen_assertion`, created automatically) makes each AuthnRequest id single-use: the
  response's `InResponseTo` must consume a pending request exactly once, and the RelayState
  recorded at issue time pins the round-tripped value against tampering. Each assertion id is
  accepted at most once until its `NotOnOrAfter`. Because the state is in the shared database,
  replays are rejected on any node of a multi-node deployment.
- **Clock skew.** Time-bound conditions (`NotBefore`, `NotOnOrAfter`, subject confirmation expiry)
  allow a fixed five minutes of skew; this is not configurable.
- **IdP-initiated SSO is off by default.** An unsolicited response — one answering no pending
  request — is rejected unless `allowIdpInitiated: true`. Leave it off unless your IdP portal
  starts logins itself.
- **Generic failures.** A validation failure answers `401` with a generic body; assertion contents
  are never echoed back or logged.
- **SSO-only mode.** Hide the password form with
  `tesseraql.console.login.password.enabled: false`, leaving SAML as the only sign-in; the account
  page then tells users their credentials are managed by the identity provider, and password
  change is unavailable (`TQL-ACCOUNT-4803`).

## Troubleshooting

SP failures return generic JSON — `401 {"error": "SAML authentication failed"}` for a validation
failure, `400 {"error": "Invalid SAML request"}` for a malformed one — with no SAML-specific
`TQL-*` runtime codes. The usual causes:

- **Every login fails with 401.** The pinned key does not match what the IdP signs with — most
  often a rotated IdP certificate. Refresh `idp.metadata` (or `idp.publicKey`). Also check that
  the IdP sends the exact `sp.audience` as the assertion audience and the exact `sp.acsUrl` as the
  recipient — a scheme, host, or trailing-slash mismatch fails validation.
- **401 only for IdP-portal logins.** Unsolicited responses are rejected by default; set
  `allowIdpInitiated: true` if users start from the IdP's app portal.
- **401 after sitting on the IdP login page.** Pending AuthnRequests expire after ten minutes;
  the returning response no longer matches a pending request. Signing in again succeeds.
- **401 for one particular user, others fine.** With `link.enabled: true` and provisioning off,
  a federated login with no matching local account is rejected — create the local user (or enable
  `link.provision`).
- **No "Sign in with SAML" on the login page.** `enabled` is not `true`, the `tesseraql-saml` jar
  is not on the classpath, or `sp.acsUrl` / `idp.ssoUrl` is missing (the login route only installs
  when both are known). A plugin allowlist (`tesseraql.plugins.allowlist`) must include `saml` if
  one is declared.

## Related pages

- [authentication.md](authentication.md) — the principal model, browser sessions, the login page,
  and OIDC (the other browser SSO method).
- [account.md](account.md) — what signed-in SSO users see on the account surface.
- [credential-lifecycle.md](credential-lifecycle.md) — password reset, invitations, and TOTP for
  the local realm; SSO-managed credentials live at the IdP instead.
