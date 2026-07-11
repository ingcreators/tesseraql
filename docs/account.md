# The account surface — user menu, preferences, self-service settings

Every business application needs the same chrome: a user menu in the shell (avatar,
name, sign-out), and a settings surface where end users pick their language, theme, and
notification preferences — the shell-bar / user-menu / settings-dialog trio familiar
from enterprise UI platforms. In TesseraQL this surface is framework-owned: a reserved
shell region, a managed preference store, a bundled `/_tesseraql/account` app, and
declared extension points for app-specific settings. The settings live as sections on
the account page rather than separate pages.

The result for an end user of any app: sign in, switch language and theme (both
persisted server-side, effective everywhere they sign in), opt out of a notification
channel, sign out other sessions, and change a local password — with the app
contributing nothing but an optional `preferences.yml`. An SSO deployment of the same
app shows the provider-managed states instead.

## The preference store

A `PreferenceStore` SPI in `tesseraql-core`, with the JDBC implementation in
`tesseraql-operations`:

```java
public interface PreferenceStore {
    Map<String, String> preferences(String tenantId, String subject);
    void put(String tenantId, String subject, String key, String value);
    void remove(String tenantId, String subject, String key);
}
```

Backed by a managed table (default / Oracle / SQL Server migrations):

```sql
create table if not exists tql_user_preference (
  tenant_id  varchar(64)  not null,   -- '' when the app is untenanted
  subject    varchar(255) not null,
  pref_key   varchar(128) not null,
  pref_value varchar(2000) not null,
  updated_at timestamp    not null,
  primary key (tenant_id, subject, pref_key)
);
```

Keys are namespaced and bounded — the store never accepts arbitrary shapes from the
browser:

- `ui.locale`, `ui.theme` — framework-owned, validated against allow-lists (locale must
  negotiate against the app's supported tags; theme is `light` | `dark`).
- `notify.<channel>.optOut` — `"true"` when the user opted out of a user-facing channel.
- `app.<key>` — only keys declared in `config/preferences.yml` (see
  [App-declared preference groups](#app-declared-preference-groups)) are writable.

The subject is **always** the authenticated session principal's; no account route reads
or writes another subject's preferences, by construction (the subject never comes from
request input). Reads go through a bounded per-subject TTL cache: default 30 s, local
writes invalidate immediately, cross-node staleness is bounded by the TTL. The table's
schema is ensured at boot when the account surface is enabled.

## The shell account region

`HtmlResponseRenderer` publishes a third reserved model variable beside `_csrf` and
`_menu`: when the request carries a browser session principal,

```
_account = { name, initials, accountHref: /_tesseraql/account, logoutHref: /_tesseraql/logout }
```

and the shared shell renders an `hc-avatar` button with an `hc-popover` menu (account
settings, sign out) in the header. Studio, the docs portal, and the ops console inherit
it through the same shell — one consistent chrome, zero app code. Apps that replaced
the shell keep the documented `_account` contract, exactly like `_menu`. Requests
authenticated by bearer/API-key/mTLS (no browser session) leave `_account` unset and
the shell renders without the region.

## The bundled account app

`/_tesseraql/account` ships as a classpath system app beside `auth-ui` (the login
page), mounted through the same `AppSourceProvider` seam and — like the login page —
enabled by default when console login is enabled, with a kill switch:

```yaml
tesseraql:
  apps:
    account:
      enabled: false   # default true when console login is enabled
```

Its pages are ordinary TesseraQL routes (query-html / command-json — the surface uses
the same DSL as your apps, like Studio), session-authenticated, CSRF-protected on
writes, and they ride the existing telemetry/audit surfaces like any route. The pages:

- **Profile**: the principal's display name, login id, tenant, roles — read-only facts
  from the session, so a user can see who the system thinks they are.
- **Language**: writes `ui.locale`; takes effect on the next request through the
  locale chain below.
- **Appearance**: writes `ui.theme` and mirrors it into a `tesseraql_theme` cookie so
  pre-login pages (the login screen itself) render in the chosen theme without a store
  lookup, and without a flash on first paint.
- **Notifications**: opt-out toggles for the channels the operator marked user-facing.
- **Out of office**: the standing absence rule — a delegate and a window — strictly the
  caller's own; see [delegation](delegation.md).
- **Sessions**: the active-session count and list (signed-in / expires) and a single
  **Sign out other sessions** action, served by the runtime-wired
  `POST /_tesseraql/logout-others` beside login/logout (CSRF-checked there explicitly).
  Session ids never reach the template, which is also why no row is marked "this
  device" — the trade for keeping ids out of the page model.
- **Password**: local-realm credential change (current password verified first). When
  sign-in is SSO-only the page states that credentials are managed by the identity
  provider.

## Locale and theme wiring

**Locale.** The i18n `preference:` source list
([internationalization](internationalization.md)) accepts a source kind,
`preference.<key>` — the full preference key after the prefix — resolved through the
`PreferenceStore` for the authenticated subject. The **default** source list is

```yaml
tesseraql:
  i18n:
    preference: [preference.ui.locale, principal.claim.locale]
```

so the language a user picks takes effect with zero configuration; operators reorder by
declaring the list explicitly (precedence stays operator-ordered — the framework does
not hardcode whether a stored preference beats an IdP claim once a list is declared).
An unsupported stored tag falls through to the next source, exactly like every other
source in the chain.

**Theme.** The shell resolves a reserved `_theme` variable: the stored `ui.theme` when
a session subject has one, else the `tesseraql_theme` cookie, else `tesseraql.ui.theme`
from config — and the template falls back to `dark` when nothing chose. Values outside
`light` | `dark` are ignored (cookies are attacker-writable; the value is an enum,
never echoed markup). When the stored choice differs from the request's cookie, the
renderer re-syncs the cookie on the response — that is what carries a signed-in choice
onto pre-login pages like the login screen, with no store lookup there.

**Toggle.** Beyond the account page's radio form, the signed-in shell header offers the
UI kit's one-click toggle (`data-hc-theme-toggle`, the `installThemeToggle` behavior,
hc 0.1.9): it flips `data-theme` on `<html>` instantly — no round trip — and fires
`hc:themechange`, which the framework bootstrap mirrors to the appearance route so the
stored preference stays the source of truth. The toggle deliberately carries no
`data-persist`: the kit's localStorage persistence would shadow the preference and
desynchronize devices. It renders only when the account app is mounted (same rule as
the settings link — the chrome never posts to a 404), and its accessible name comes
from the kit catalog (`themeToggle.label`). See the blessed pattern in
[hypermedia-ui.md](hypermedia-ui.md) for app-authored toggles.

## Notification opt-out

Two additions to [notifications](notifications.md), both opt-in so existing apps are
untouched:

- A channel the operator marks `userOptOut: true` under
  `tesseraql.notifications.channels.<name>` appears on the account notifications page.
  Operational/system channels never show up.
- A `notify:` step gains an optional `recipient:` expression resolving to a subject.
  When present, the enqueue path (the notification outbox sink) consults
  `notify.<channel>.optOut` for that subject and skips enqueueing — one log line, no
  outbox row, no partial delivery state. Channel-level notifications without
  `recipient:` are delivered regardless of anyone's preferences.

## Sessions and password

`SessionStore` provides two default methods (so custom implementations keep compiling):

```java
record ActiveSession(String sessionId, Instant createdAt, Instant expiresAt) {}
default List<ActiveSession> sessionsFor(String subject) { return List.of(); }
default void invalidateOthersFor(String subject, String keepSessionId) {}
```

`tql_session` has a nullable, indexed `subject` column; new sessions populate it. Rows
created before an upgrade to this schema have no subject, are not listed, and age out
at their expiry — documented rather than backfilled, since the principal JSON is the
only source and a scan-and-parse backfill buys nothing a TTL doesn't.

Password change is a runtime-provided service: verify the current credential, hash, and
update through the local identity contract pack. It is registered only when password
login is active; the account app renders the SSO state otherwise.

## App-declared preference groups

The piece that turns a settings page into a platform surface. An app declares:

```yaml
# config/preferences.yml
preferences:
  - key: pageSize
    label: app.pref.pageSize      # message-catalog key
    type: choice                  # boolean | choice | text
    options: ["10", "25", "50"]
    default: "25"
```

The account settings page renders a section per app with these fields; values persist
under `app.<key>` and only declared keys with valid values are writable
(`TQL-ACCOUNT-4801` / `4802` otherwise). Routes, templates, and 2-way SQL read them
through a `preference.<key>` namespace that resolves declared defaults when the user
never chose — so `/* preference.pageSize */'25'` in a query is the whole integration.

Lint (`TQL-YAML-1030` parse/key/duplicate, `1031` unknown type, `1032` choice without
options, `1033` default outside the acceptable values) validates the file exactly as
the runtime loads it. A `preference` NOTE coverage kind lists the declared keys, like
the `oidc` kind. The account page resolves each `label` through the message catalog
and falls back to the raw key untranslated; the `preference.<key>` namespace feeds
route expressions, templates, and `sql.params` mappings — declared keys only, stored
value else declared default.

## Error taxonomy

A `TQL-ACCOUNT` domain, codes in the 48xx block: `4801` undeclared preference key,
`4802` invalid value for a declared preference, `4803` password change unavailable
(SSO-only sign-in), `4804` current-password mismatch, `4805` account surface disabled.

## Security posture

- The subject is always the session principal's — cross-subject reads/writes are
  impossible by construction, not by check.
- All writes are CSRF-protected command routes; values are length-capped and validated
  against the declared shape (allow-list for framework keys, `preferences.yml` for app
  keys) before they reach the store.
- Stored values are rendered through the normal escaping pipeline and never
  interpolated into headers; the theme cookie is an enum lookup, never echoed.
- Preferences are explicitly the wrong place for secrets; nothing in the surface
  accepts a secret reference.
- Session listing renders no session identifiers; the only mutation is
  "invalidate everything but the current session".

## Design notes

The account surface is a bundled system app riding the shared shell — over scaffolded
pages (updates would not flow with the framework) and over a mountable app (setup where
zero-setup is the point). App-side customization stays real through the existing
ladder: shell/pattern overrides (L2) restyle the surface, `menu.yml` decides its links,
`preferences.yml` extends its content, and the kill switch removes it entirely for apps
that want to own the surface themselves.

An app author *could* build a settings page by hand, but could not reach the shell
chrome, the locale-resolution chain, the notification dispatch path, or the session
store — those integration points are framework-internal, which is why the framework
owns this surface rather than a scaffold.
