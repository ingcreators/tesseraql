# Pre-1.0 contract bug fixes

> **Status: waves A and B complete.** Wave A: design #613, slices #614 (tracks A+B),
> #617 (track C; #615 was its base-branch-closed predecessor), #616 (tracks D+E+F) —
> all merged 2026-08-08; track C shipped with a three-form unit contract test
> (`x`/`/x`/`//x`), the rooted-vs-non-rooted VFS IT stays future hardening. Wave B
> (tracks G/H/I — the safe-default flips) shipped 2026-08-08 in one slice.
> The contract-consistency sweep
> (2026-08-08, the same pass that shipped transition-engine track F) surveyed every
> recorded deferral, the YAML surface, and the HTTP/JSON surfaces for places where a
> defect is about to be frozen into the 1.0 compatibility contract (roadmap Phase 34).
> This document is the first wave — the six findings that are *bugs*, not taste: each
> one contradicts either the framework's own documentation, its own sibling code path,
> or its own schema. All land as one-shot pre-1.0 breaks under the latest-release-only
> support posture; none warrants a dual-emit window. The remaining waves (default
> values, vocabulary unification, open-decision closure) are recorded at the end as
> candidates for their own designs.

## Why now

Phase 34 freezes YAML schema v1 and the JSON contracts. Every item below is cheap
today and expensive-to-impossible after the freeze: a status code, a default
resolution rule, a schema constraint, and a payload key are exactly the things a
compatibility contract exists to pin. Fixing them after 1.0 would mean breaking the
contract; not fixing them would mean documenting behavior the code contradicts.

## Track A — the operations API must not answer 200 with an error body

`OperationsRouteBuilder` defines a `NOT_FOUND` error envelope **as a value** and
returns it from four JSON handlers (`redeliverOutboxEvent`, `runJob`,
`cancelExecution`, `executionDetail`); `jsonProcessor` then stamps
`HTTP_RESPONSE_CODE = 200` unconditionally. So
`GET /_tesseraql/ops/batch/executions/no-such` answers **200**
`{"error":{"code":"TQL-BATCH-4040",…}}` — while three other authorities promise 404:
`ErrorResponseRenderer.httpStatus` maps `BATCH 4040 → 404` (with a comment claiming it
*matches* the ops API), `docs/jobs.md` documents 4040 as not-found, and the sibling
`transferFile` handler in the same class sets 404 explicitly. A client checking
`response.ok` treats a missing execution as success.

**Fix**: the handlers throw `TqlException(BATCH 4040)` and the standard error path
renders it (the same shape `NOT_RUNNING`/4042 already takes, which correctly answers
409 today). The `NOT_FOUND` constant is deleted; `transferFile`'s hand-serialized 404
moves onto the same throw. The integration tests gain the missing
`statusCode()` assertions — they currently assert on the body only, which is how the
wrong status survived.

## Track B — the SEC domain's default HTTP status inverts to 500

`ErrorResponseRenderer.httpStatus` enumerates four SEC codes and sends **everything
else in the domain to 401**. The SEC domain is the whole security namespace, not an
authentication-failure namespace; the default currently classifies server faults as
"Unauthorized": PBKDF2 failure (5001), unsupported stored hash (5002), unsupported
`auth=` mode — a config error (4000), IdP call failure (4140), SAML metadata host
denied (4086/4087), remote source without credential (4088/4089), invite surface
unconfigured (4120), egress host not allow-listed
(4141). The sharpest instance: `OidcRouteBuilder` carries a comment explaining that
collapsing server faults into caller-fault statuses "told an operator their request
was wrong when the truth was that the server failed" — and the SEC default silently
undoes that fix by rendering `FederationErrors.FAILED` as 401. A 401 also invites
clients into token-refresh retry loops against a genuinely broken server.

**Fix**: invert the default to 500 and enumerate the genuine caller-fault codes,
matching every other domain in the switch (LANE, IDEM, DECISION all default 5xxx→500):

- 401: 4011 (unauthenticated), 4012/4013 (webhook signature/timestamp — the caller's
  credential material is wrong or stale), and any other true credential failures
  found in the implementation sweep;
- 403: 4031, 4032 (unchanged);
- 409: 4014 (webhook replay, unchanged);
- **500: everything else** — including 4140, whose failure is the federation
  boundary's, not the caller's.

The full classification, from a sweep of every `TqlDomain.SEC` code minted in main
code (slice 1):

| Status | Codes | Why |
|---|---|---|
| 401 | 4011 (unauthenticated), 4012 (webhook signature), 4013 (webhook timestamp stale) | the caller's credential material is wrong or stale |
| 403 | 4031 (forbidden), 4032 (CSRF) | unchanged |
| 409 | 4014 (webhook replay) | unchanged |
| 500 | 4000 (unsupported `auth=` mode), 4001 (the authenticator the route needs is not configured), 4085 (copilot endpoint/egress config), 4086/4087 (SAML metadata host/URL), 4088/4089 (remote credential missing/method), 4120 (invite surface unconfigured), 4132/4135 (invalid defaults documents), 4140 (federation failure), 4141 (push egress host), 5001/5002 (password crypto) | configuration and server faults — none are the caller's |

No test asserted 401 for any code in the 500 row (most surface at boot or lint
time); the OIDC/SAML integration tests' 401 assertions are all on the 4011 path
and hold.

## Track C — a remote poll/push `path:` means what it says

`RemoteFileUris.remoteUri` strips a leading `/` from the declared `path:` before
appending it to the endpoint URI — but Camel's FTP/SFTP URI grammar already treats
the first slash after the authority as a separator, so the double strip turns the
documented `path: /outbound/orders` into a **login-home-relative** directory.
Absolute paths are only expressible through an undocumented `//outbound/orders`
escape. The failure is silent in both directions: an app polling an empty
home-relative directory forever looks healthy. (Recorded as the one unshipped
finding of docs/poll-connector-hardening.md; the SFTP IT roots its test user at `/`,
which is why CI never sees the difference.)

**Fix**: delete the strip. `path: outbound/orders` is home-relative,
`path: /outbound/orders` is absolute — exactly what the reference already says.
Leading slashes normalize to one, so the old `//` escape degrades gracefully into
the same absolute meaning rather than breaking. Applies to both poll sources and
push targets (they share `remoteUri`). One-shot break, CHANGELOG-recorded: a
deployment that relied on the home-relative reading of a leading-slash path must
drop the slash. The poll-connector-hardening open question (a one-release lint
naming both readings) is closed as **no** — pre-1.0, the clean contract wins.

## Track D — `rows` is a list everywhere; the transfer status said integer

Every payload in the framework uses `rows` for a list of records (query results,
IAM results, HTTP-source results, view bindings — `docs/pagination.md` documents
it) — except the file-transfer status endpoint, where `rows` is a **long count**.
The OpenAPI generator even hardcodes the convention (`*.rows → array`,
`*rowCount → integer`), so it would emit a wrong schema for the transfer endpoint
today.

**Fix**: the transfer status payload (and the inline-import result) rename
`rows` → `rowCount`, matching the query metadata vocabulary. The OpenAPI
`TransferStatus` component and `docs/file-transfers.md` follow.

## Track E — the decisions schema rejects a shipped gallery decision

`tesseraql-decisions-v1.schema.json` requires `rows:` on every decision while its
own description says "exactly one of `rows:`/`source:`" — so the table-backed
gallery showpiece (`procurement-app/decisions/delivery-auto-accept.yml`, which
declares `source:` and no `rows:`) is schema-invalid in every editor.

**Fix**: `required: ["inputs", "outputs"]` plus a `oneOf` requiring exactly one of
`rows`/`source`.

## Track F — the editor schema rejects kinds the framework itself scaffolds

The app schema's `kind:` enum is `route | job | view`, but the framework parses ten
kinds: `workflow`, `scope`, `attachment` (SimpleYamlParser) and the MCP kinds
`tool`, `resource`, `ui`, `prompt` (ManifestLoader — which reuses the **route
model**, so those documents are shape-compatible with the app schema already). The
scaffolded `.vscode/settings.json` maps `mcp/**/*.yml` to the app schema, so the
framework's own generated settings red-flag a correct `kind: tool` document; and
`workflow/`, `scope/`, `attachments/`, `calendars/` are associated with no schema at
all.

**Fix**: extend the `kind` enum to all ten kinds (the schema keeps
`additionalProperties: true` by design, so kind-specific keys stay legal); associate
`workflow/**`, `scope/**`, `attachments/**` with the app schema in the scaffolded
settings; add a `tesseraql-calendars-v1.schema.json` (calendars are a
shared-definitions-style document — `version:` + a `calendars:` map — like domains
and rules) and associate `calendars/**`. Scaffold-demo regenerates via
`-Dtesseraql.scaffold.regenerate=true` (never hand-edited).

## Slices

1. **HTTP truthfulness** — tracks A + B: ops handlers throw, `NOT_FOUND` deleted,
   SEC default inverted with the classification table recorded here, status
   assertions added to the ops/batch ITs and the federation ITs.
2. **Remote path resolution** — track C: strip deleted, leading-slash
   normalization, docs (`file-transfers`/connectors reference,
   poll-connector-hardening closure), IT coverage that pins home-relative vs
   absolute against a rooted and a non-rooted virtual FS user.
3. **Schema and payload honesty** — tracks D + E + F: transfer `rowCount`, OpenAPI
   component fix, decisions schema `oneOf`, `kind` enum + calendars schema +
   scaffolded associations, scaffold-demo regen.

## Wave B — defaults (tracks G/H/I, shipped 2026-08-08)

The three places where the unsafe behavior was the default. Each is a one-shot
pre-1.0 break the compatibility contract would otherwise freeze; all three flip to
the safe default with the old behavior as the explicit opt-in.

### Track G — sessions default to the store that survives

`tesseraql.sessions.store` defaulted to `memory`: sessions gone on every restart,
multi-node fundamentally broken (a login on one node unknown to the next), and
historically the only store that ignored its own TTL key. Now **`jdbc` is the
default** — one `tql_session` table on the framework datasource, shared across
nodes, prunes on create — and `memory` is the explicit per-node opt-in for
embedders and tests. (The parity sweep's open question 1, closed yes.)

### Track H — a job firing that finds the previous run still running skips

`overlap:` defaulted to `concurrent`: a scheduled job whose previous execution had
not finished — nearly always a symptom — got a second run stacked on top, the
fault-amplifying answer that only shows under load. Now **`skip` is the default**:
the firing is recorded as a `SKIPPED` execution naming the running one (auditable,
non-destructive), and a job that is genuinely safe to overlap declares
`overlap: concurrent`. Deny-by-default, applied to time.

### Track I — the live-stream global cap refuses instead of evicting

At the global cap (256 streams) a new `/_tesseraql/events` subscription evicted the
oldest stream **of any user** — silently ending someone else's live view to serve
the newcomer. Now the global cap **refuses** the new subscription with
`TQL-RATE-5030` (503 + `Retry-After`, rendered before the stream opens — the
subscribe moved from the producer into the SSE `begin`, which also closes the
connect-to-subscribe signal gap by construction). The per-subject cap (4) keeps
evicting the subject's own oldest stream — there the victim is the same user
opening one tab too many. Both caps became configurable:
`tesseraql.live.maxPerSubject` / `tesseraql.live.maxTotal`.

## Out of scope (the remaining waves)

Recorded by the same sweep, each a candidate for its own design:
- **Vocabulary wave** (the YAML v1 rename batch + HTTP unification): job
  `params:` → `input:`, `csrf:` enum everywhere, `http-call:` → `httpCall:`,
  `header:`/`label:`, poll `source:`/push `target:` → `transport:`,
  `expect.rows` → `rowCount`, root `policy:` → `admission:`, decision `out:` →
  `outputs:`/`subtree`, enum-value casing, `notify:` shape, `version:` required
  everywhere, typed `binds:`; ISO-8601 timestamps on the ops wire, `Location` on
  201/202, charset constant, htmx redirect helper, `Retry-After`, framework
  pagination.
- **Decision-closure wave**: `modules.lock` format, `stamp:` × scope threat-model
  revisit, unknown `domain:` as a load error everywhere, batch `/*%scope */` lint,
  `bean` baseline-denied verification, dispatch `transition` opt-out disposition,
  ambient namespace disposition (`principal.*`/`audit.*`/`tenant.id` declared as
  the contract).
