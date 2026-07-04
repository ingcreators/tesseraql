# The account surface — user menu, preferences, self-service settings

Status: design accepted 2026-07-04 (roadmap Phase 48, opening Horizon 9). Slices land
incrementally; each section below names the slice that delivers it. **All five slices
are delivered — Phase 48 is complete and milestone M13 is met**: the preference store,
the shell user menu, the profile page, the language + appearance settings, the
notification opt-out, the session + local-password self-service, and the app-declared
preference groups (the settings live as sections on the account page rather than
separate pages).

Every business application re-implements the same chrome: a user menu in the shell
(avatar, name, sign-out), and a settings surface where end users pick their language,
theme, and notification preferences — the shell-bar / user-menu / settings-dialog trio
familiar from enterprise UI platforms. TesseraQL apps assemble their *pages*
declaratively, but this cross-cutting surface has no framework answer yet: an app that
wants a settings screen hand-builds the preference table, the pages, and the wiring
into locale resolution — and gets none of it in Studio, the docs portal, or the ops
console, which share the same shell.

Phase 48 makes the account surface framework-owned: a reserved shell region, a managed
preference store, a bundled `/_tesseraql/account` app, and declared extension points
for app-specific settings.

## What already exists (and what this design adds)

The framework anticipated most of this surface; the recon that grounded this design
found the following seams already in place:

| Seam | State today | This design adds |
| --- | --- | --- |
| Shared shell (`tql/shell.html`) | Renders reserved `_csrf` / `_menu` model variables; no account region; `data-theme="dark"` hardcoded | A reserved `_account` variable and an avatar + popover menu region; theme becomes model-driven |
| Locale resolution (`LocaleResolution`, Phase 22) | Preference sources (`principal.*`, `query.*`) → `Accept-Language` → default — a user-preference hook by design | A `preference.<key>` source kind backed by the store |
| Sessions (`SessionStore`) | Create / resolve / invalidate; `POST /_tesseraql/login`, `GET /_tesseraql/logout`; `tql_session` has no subject column | Listing by subject + "sign out other sessions" (V2 migration) |
| Bundled system apps | `auth-ui` (login page) ships as a classpath app via the `AppSourceProvider` ServiceLoader seam | An `account` app mounted the same way |
| UI kit | `hc-avatar`, `hc-popover`, `hc-menu`, `hc-dialog`, `hc-switch`, `data-theme` light/dark all ship in hc | No new kit components needed |
| Notifications (Phase 20) | Channel-addressed; no recipient concept | An optional `recipient:` and per-user channel opt-out |
| Identity | Local realm as a SQL contract pack (`tesseraql-identity`); SSO via OIDC/SAML | Self-service password change for the local realm only |
| Managed tables | `tql_*` pattern with vendor migrations and boot-time `ensureSchema` | `tql_user_preference` |

The honest delta over hand-rolling: an app author *can* build a settings page today,
but cannot reach the shell chrome, the locale-resolution chain, the notification
dispatch path, or the session store — the integration points are framework-internal.
That is exactly the part Phase 48 owns; the pages themselves are the smaller half.

## The preference store (slice 1)

A `PreferenceStore` SPI in `tesseraql-core` (the `EventChannelStore` / `OrgUnitStore`
pattern), with the JDBC implementation in `tesseraql-operations`:

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
- `app.<key>` — only keys declared in `config/preferences.yml` (slice 5) are writable.

The subject is **always** the authenticated session principal's; no account route reads
or writes another subject's preferences, by construction (the subject never comes from
request input). Reads go through a bounded per-subject TTL cache (the `JwksKeySource`
caching spirit: default 30 s, local writes invalidate immediately, cross-node staleness
is bounded by the TTL and documented). `ensureSchema` runs when the account surface is
enabled.

## The shell account region (slice 1)

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
the shell renders as today.

## The bundled account app (slice 1, then grows)

`/_tesseraql/account` ships as a classpath system app beside `auth-ui`, mounted through
the same `AppSourceProvider` seam and — like the login page — enabled by default when
console login is enabled, with a kill switch:

```yaml
tesseraql:
  apps:
    account:
      enabled: false   # default true when console login is enabled
```

Its pages are ordinary TesseraQL routes (query-html / command-json — the surface
dogfoods the DSL, like Studio), session-authenticated, CSRF-protected on writes, and
they ride the existing telemetry/audit surfaces like any route. The pages, by slice:

- **Profile** (slice 1): the principal's display name, login id, tenant, roles —
  read-only facts from the session, so a user can see who the system thinks they are.
- **Language** (slice 2): writes `ui.locale`; takes effect on the next request through
  the locale chain below.
- **Appearance** (slice 2): writes `ui.theme` and mirrors it into a `tesseraql_theme`
  cookie so pre-login pages (the login screen itself) render in the chosen theme
  without a store lookup, and without a flash on first paint.
- **Notifications** (slice 3): opt-out toggles for the channels the operator marked
  user-facing.
- **Sessions** (slice 4): the active-session count and list (signed-in / expires) and a
  single **Sign out other sessions** action, served by the runtime-wired
  `POST /_tesseraql/logout-others` beside login/logout (CSRF-checked there explicitly).
  Session ids never reach the template, which is also why no row is marked "this
  device" — the honest trade for keeping ids out of the page model.
- **Password** (slice 4): local-realm credential change (current password verified
  first). When sign-in is SSO-only the page states honestly that credentials are
  managed by the identity provider — the copilot disabled-state pattern.

## Locale and theme wiring (slice 2)

**Locale.** The i18n `preference:` source list (Phase 22) accepts a new source kind,
`preference.<key>` — the full preference key after the prefix — resolved through the
`PreferenceStore` for the authenticated subject. The **default** source list is now

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

**Theme.** The shell's hardcoded `data-theme="dark"` becomes a reserved `_theme`
variable: the stored `ui.theme` when a session subject has one, else the
`tesseraql_theme` cookie, else `tesseraql.ui.theme` from config — and the template
falls back to `dark`, today's look, when nothing chose. Values outside
`light` | `dark` are ignored (cookies are attacker-writable; the value is an enum,
never echoed markup). When the stored choice differs from the request's cookie, the
renderer re-syncs the cookie on the response — that is what carries a signed-in
choice onto pre-login pages like the login screen, with no store lookup there.

## Notification opt-out (slice 3)

Two additions, both opt-in so existing apps are untouched:

- A channel the operator marks `userOptOut: true` under
  `tesseraql.notifications.channels.<name>` appears on the account notifications page.
  Operational/system channels never show up.
- A `notify:` step gains an optional `recipient:` expression resolving to a subject.
  When present, the enqueue path (the Phase 20 outbox sink) consults
  `notify.<channel>.optOut` for that subject and skips enqueueing — one log line, no
  outbox row, no partial delivery state. Channel-level notifications without
  `recipient:` are delivered regardless of anyone's preferences, and the cookbook says
  so plainly.

## Sessions and password (slice 4)

`SessionStore` grows two default methods (so custom implementations keep compiling):

```java
record ActiveSession(String sessionId, Instant createdAt, Instant expiresAt) {}
default List<ActiveSession> sessionsFor(String subject) { return List.of(); }
default void invalidateOthersFor(String subject, String keepSessionId) {}
```

`tql_session` gains a nullable, indexed `subject` column (V2 migration); new sessions
populate it. Rows created before the upgrade have no subject, are not listed, and age
out at their expiry — documented rather than backfilled, since the principal JSON is
the only source and a scan-and-parse backfill buys nothing a TTL doesn't.

Password change is a runtime-provided service (the `studio.*` provider pattern):
verify the current credential, hash, and update through the local identity contract
pack. It is registered only when password login is active; the account app renders the
honest SSO state otherwise.

## App-declared preference groups (slice 5)

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
the runtime loads it. A `preference` NOTE coverage kind lists the declared keys, the
`oidc` precedent. The account page resolves each `label` through the message catalog
and falls back to the raw key untranslated; the `preference.<key>` namespace feeds
route expressions, templates, and `sql.params` mappings — declared keys only, stored
value else declared default. The gallery's inventory app dogfoods the file.

## Error taxonomy

A new `TQL-ACCOUNT` domain, codes in the 48xx block: `4801` undeclared preference key,
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
- Preferences are explicitly the wrong place for secrets; the cookbook says so, and
  nothing in the surface accepts a secret reference.
- Session listing renders no session identifiers; the only mutation is
  "invalidate everything but the current session".

## Decision record

**Delivery form** (decision point 10): a bundled system app riding the shared shell —
over scaffolded pages (updates would not flow with the framework) and over a mountable
app (setup where zero-setup is the point). App-side customization stays real through
the existing ladder: shell/pattern overrides (L2) restyle the surface, `menu.yml`
decides its links, `preferences.yml` extends its content, and the kill switch removes
it entirely for apps that want to own the surface themselves.

## Slices

1. **Preference core + chrome**: `PreferenceStore` SPI + JDBC store + `tql_user_preference`
   migrations + cache; `_account` reserved variable + shell avatar/popover region;
   the account app skeleton with the profile page; enablement flags.
2. **Language + appearance**: the `preference.<key>` locale source; `_theme` + cookie
   mirror replacing the hardcoded `data-theme`; the two settings pages.
3. **Notification opt-out**: `userOptOut` channel marker, `recipient:` on `notify:`,
   the enqueue-time check, the notifications page.
4. **Sessions + password**: `SessionStore` listing/invalidate-others + `tql_session` V2;
   the sessions page; local-realm password change with the honest SSO state.
5. **App preference groups**: `preferences.yml`, lint + NOTE coverage kind, the
   `preference.*` read namespace, cookbook.

Milestone **M13** closes the phase: an end user of a gallery app signs in, switches
language and theme (both persisted server-side, effective everywhere they sign in),
opts out of a notification channel, signs out their other sessions, and changes their
local password — with the app contributing nothing but `preferences.yml`; an SSO
deployment of the same app shows the honest provider-managed states instead.
