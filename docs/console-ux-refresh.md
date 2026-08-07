# Console UX refresh

> **Status: complete.** All five slices landed 2026-08-07 (#588 auth pages, #589
> silent-mutation flashes, #590 jobs refresh + data-numeric, #591 card anatomy +
> identity states, #592 list search + stat tiles), plus a follow-up slice 6: the
> generated view templates (`tql/view/*`), the `tesseraql new` starter page, and the
> gallery apps moved to the card anatomy too, and the global bare-`h2` scale left
> `tesseraql.css` — no user apps exist yet, so the pre-1.0 rendering-contract break was
> taken deliberately (card headers are now the one heading idiom everywhere).
> Originally: a template-by-template UX review of every TesseraQL-provided
> surface outside Studio — the Operations Console (`tesseraql-ops-ui`, 7 templates), IAM
> Admin (`tesseraql-identity`, 4), the account surface (`tesseraql-camel-runtime`
> `apps/account`, 2), and the standalone auth pages (`apps/auth-ui`, 4) — against the
> patterns the Studio UX refresh (docs/studio-ux-refresh.md, #574–#585) established, and
> the slice plan that follows from it. This is an internal design document (docs-site
> `EXCLUDED`).

## Motivation

The Studio refresh left the framework with a settled UI grammar: card anatomy
(`hc-card__header/body/footer`), a flash alert after every mutation, one semantic color
vocabulary, scroll containers on wide tables, header-search on unbounded lists, confirm
gates on destructive actions, and — since hc 0.1.13 — `data-numeric` end-alignment on
numeric columns. The other consoles predate all of it. A full review of their 17 templates
plus their route redirects found the same three classes of problems the Studio review did,
including two findings that are outright UX bugs:

1. **Correctness bugs.**
   - `POST /_tesseraql/account/password` redirects to
     `/_tesseraql/login?reason=password-changed` — but `login.html` never reads `reason`.
     Changing a password invalidates every session (by design), so the user is signed out
     everywhere and lands on a login page that says nothing about why.
   - The Batch Jobs page auto-refreshes `#page-content` every 15 s with an
     `outerHTML` swap, and the per-job Run forms — including declared-parameter inputs —
     live inside that region. Typing a parameter value races the refresh; the swap
     silently discards the user's input.
2. **Missing system plumbing.** Silent mutations, the exact gap Studio slice 2 closed:
   IAM enable/disable/revoke redirect with no flash param, and on the account surface
   language/appearance/notifications/app-settings/delegation/shortcuts — and even TOTP
   enrollment confirmation — complete without a word. Separately, the four standalone auth
   pages link `hc.min.css` directly and never load the neutral-ramp override, so since the
   app-wide slate default (#585) the very first screen a user sees renders in the default
   gray while everything after sign-in is slate.
3. **Divergent local dialects.** Bare `<section class="hc-card"><h2>` everywhere (the
   global `h2` override in `tesseraql.css` exists only because these consoles are
   unconverted); plain `hc-table` without a scroll container under long user-agent
   strings; `INVITED` users badged `error` red; Enable and Disable both always rendered
   regardless of the user's current status; `hc-radio-label` wrapping switches where
   Studio uses `hc-switch-label`; "Sign out other sessions" unconfirmed on the account
   page while the identical IAM action is confirm-gated; no search on the IAM users list
   and fixed newest-200 views with no filter on audit/outbox/transfers; count/duration
   columns without `data-numeric`.

## Design decisions

### Reuse, don't re-derive

Every fix applies a pattern Studio already shipped; this campaign introduces no new
vocabulary. Flash alerts follow the slice-2 recipe (declare the input param, put it on the
model, render an `hc-alert` at the top of `#page-content`). Card anatomy, `data-numeric`,
`hc-table-scroll`, header-search, and confirm gates are all verbatim applications.

### Auto-refresh must never eat input

Live pages keep their 15 s refresh, but interactive forms may not sit inside the swapped
region. Jobs moves the refresh to the data that actually changes (the table region via
`hx-select`), and any page that later gains inline forms follows the same rule. A refresh
the user cannot lose work to is the contract.

### Status vocabulary for identity states

`ACTIVE` = success, `INVITED` = info (a normal in-between state, not a failure),
`DISABLED` = error. Action buttons follow state: an active user offers Disable, a disabled
or invited user offers Enable — never both.

### Standalone pages share the shell's token plumbing

The auth pages stay standalone (no nav before sign-in) but load the same conditional
`hc.tokens.neutral-<ramp>.css` link the shell does, driven by the same `_neutral` reserved
variable, so the first screen matches everything behind it.

## Slices

Each slice is one PR, independently shippable, full verify before push.

1. **Auth pages** — render `reason=password-changed` on the login page ("Password
   changed — sign in again."); add the conditional neutral-ramp link (and `data-density`
   passthrough) to login/invite/reset/reset-confirm. Extends
   `PasswordLoginIntegrationTest`.
2. **Silent mutations** — flash params + alerts for IAM enable/disable/revoke-one/
   revoke-all (users detail) and for every account-surface save (language, appearance,
   notifications, app settings, delegation save/clear, shortcuts remove, TOTP
   enable/disable, sign-out-others). Extends `IamAdminIntegrationTest` and
   `AccountSurfaceIntegrationTest`.
3. **Ops correctness + numerics** — scope the jobs auto-refresh so Run forms are never
   swapped; `data-numeric` on every numeric column across the seven ops templates
   (durations, rows, counts, lanes); the execution detail's numeric facts align too.
   Extends `OpsConsoleIntegrationTest`.
4. **Card anatomy + tables + identity states** — `hc-card__header/body/footer` across
   ops/iam/account; `hc-table-scroll` on the plain tables; delete the global `h2` override
   from `tesseraql.css` once nothing needs it; INVITED=info badge; state-dependent
   Enable/Disable; `hc-switch-label` wrappers; confirm gate on account
   sign-out-others/sign-out-device.
5. **List parity** — header-search on the IAM users list (login/name/email, server-side
   filter); filter idiom on ops audit (route/actor/status) reusing the Studio audit
   header-search shape; overview roll-ups become `.tql-stats` tiles with `data-tone`.
   Outbox/transfers keep their status chips and gain nothing new unless the filter recipe
   drops out for free — they are bounded operational views, not browsing surfaces.

## Non-goals

- No information-architecture changes: the consoles' pages, routes, and navigation stay.
- No command palette outside Studio (revisit on demand).
- No pagination for audit/outbox/transfers beyond today's caps.
- No visual redesign of the auth pages beyond token plumbing — they are deliberately
  minimal.
