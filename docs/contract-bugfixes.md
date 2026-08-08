# Pre-1.0 contract bug fixes

> **Status: design accepted; slices 1–3 pending.** The contract-consistency sweep
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
unconfigured (4120), component-guard refusal (4138), egress host not allow-listed
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

The implementation slice greps every `TqlDomain.SEC` code and records the full
classification table here.

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

## Out of scope (the remaining waves)

Recorded by the same sweep, each a candidate for its own design:

- **Defaults wave**: session store `memory` → `jdbc`; job `overlap: concurrent` →
  `skip`; LiveStreams global-cap eviction → refuse-with-error.
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
