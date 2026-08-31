# Idempotency keys for browser forms — bridging the header machinery to the hc recipe

> **Status: all three slices shipped 2026-08-31.** Slice 1 (#1085) — release-on-failure,
> the form-aware principal-folded hash, the 4221/4090 split. Slice 2 (#1086) — the
> `_idempotency` transport end to end. Slice 3 — replay-header fidelity and the
> retention prune. The decisions below are the live contract; the "gaps" read as history.
>
> Recorded 2026-08-31, as the follow-up the hc 0.4.0 adoption (#1081, #1083) left open.
> The kit's `network-retry` recipe pairs with its `idempotency-key` recipe: a Retry that
> re-issues a POST is provably safe only when the form carries a one-time key and the
> server answers a replayed key with the original response. TesseraQL already has the
> server half — the `idempotency:` route block and `Idempotency-Key` header
> ([transactional-writes.md](transactional-writes.md) "Idempotent replay"). This design
> closes the distance between that machinery and the upstream recipe contract
> (`recipes/idempotency-key/contract.md` in the hypermedia-components repository), which
> is form-shaped: a hidden field, no JavaScript, and a spend-only-on-commit rule.

## What exists

The header machinery is complete and route-declared:

- `idempotency: {required, scope, ttl}` on a route
  (`IdempotencySpec`, schema `tesseraql-route-v1.schema.json`).
- `IdempotencyProcessors.Begin` runs before request binding: `Proceed` (claim a PENDING
  row), `Replay` (set the stored response and stop the route), or `Conflict`
  (`TQL-IDEM-4090`, 409). `Complete` runs after the renderer and stores
  status/body/content-type. Wired by `RouteCompiler.applyIdempotencyBegin/Complete` on
  the json, transactional-command, template-page and MCP-tool builders.
- `JdbcIdempotencyStore` over `tql_idempotency_record` (business datasource,
  `(scope, idempotency_key)` primary key, `operations/V1` migrations on
  default/oracle/sqlserver; Flyway for postgresql/mysql/oracle/sqlserver, the store's
  `ensureSchema` for the rest).
- `RouteGovernance` already scores a write route without an `idempotency:` block.

## The five gaps

Each gap is measured against the upstream contract; the first two are defects in the
existing machinery that the form bridge would inherit, so they come first.

### 1. A non-commit outcome strands the claim (spend-on-commit violation)

`Begin` claims a PENDING row; a validation failure (`TQL-FIELD-4220`, 422) throws out of
`TransactionalCommandProcessor`, skipping every later step — `Complete` included. The
PENDING row survives until TTL, so the corrected resubmit with the same key answers
`TQL-IDEM-4090` until the record expires. The upstream contract names this exact trap:
"store-and-replay a 422 validation failure and users can never fix a validation error" —
and its rule is that the key is spent by the *commit*, not the attempt. The header API
has the same defect today; callers survive it only by minting a fresh key per attempt,
which forfeits the guarantee the key exists to give.

### 2. The request hash is blind to form bodies

`requestHash` hashes `method + path + body`. For every browser form — urlencoded and
multipart both — `RouteEdge.body` copies fields into `formFields()` and never calls
`setBody`, so the hash degenerates to `method + path`. Any two submissions of the same
route look identical: the "same key, different payload" branch cannot fire for forms at
all. (JSON callers are unaffected — their body is set.)

### 3. No key transport for forms

`Begin` reads only the `Idempotency-Key` header. A no-JS form post cannot set a header;
the recipe's transport is a hidden field, exactly like `_csrf`
(`AuthStep.csrf` already reads header-first-then-form-field for the same reason).

### 4. Nothing mints a key into rendered forms

The upstream contract: one fresh, opaque key per *rendered form* — every submit of that
form instance claims the same intent. Nothing supplies such a value to templates today.

### 5. Replays drop the headers that matter

The store keeps status/body/content-type. A replayed commit loses its `HX-Trigger`
toast and — on the PRG path — its `Location`/`HX-Redirect`, so the double-clicking user
who most needs "Order placed" is the one who does not see it.

## Decisions

1. **Release the claim on any non-commit outcome.** `Begin` records its claim on the
   exchange (a `TesseraqlProperties` key holding scope + key); `Complete` clears it
   after storing. The edge's failure path releases a still-held claim —
   `IdempotencyStore` gains `release(scope, key)`, deleting the row only while it is
   still in progress. Success stores, failure releases, and the key survives a 422 for
   the corrected resubmit. This fixes the header API and the form bridge in one move.

2. **Hash the form fields, canonically, with the principal folded in.** When the body
   is null and `formFields()` is not empty, the hash covers the sorted
   `name=value` pairs, excluding the reserved fields (`_csrf`, `_idempotency` — one
   varies by session, the other is the key itself). The authenticated principal id is
   folded into every hash: the upstream scope is "per user × per form", and folding the
   user into the hash gets that without a schema change — another user replaying a
   stolen key mismatches and is refused. File parts are excluded from the hash and
   documented as such: their bytes are never materialized on this path, and an
   attachment-bearing command should not declare `idempotency:` until someone needs it.

3. **Split the mismatch from the in-flight conflict.** Same key + different payload
   becomes 422 (`TQL-IDEM-4221`) — the upstream stance: a stale tab or a bug, rendered
   into the result region like any refusal of the request's content. Same key while the
   first request is in flight stays 409 (`TQL-IDEM-4090`) — a genuine race, and the
   4090 code keeps its meaning. Pre-1.0: the change is recorded here, not migrated.

4. **`_idempotency` is a reserved variable, minted per HTML render.** A CSPRNG value
   minted in `HtmlResponseRenderer` beside `_csrf`, published as `_idempotency`,
   rendered by `tql/view/form.html` as a hidden field, and added to
   `RequestBinder.RESERVED_FIELDS`. Per-render is per-form-instance in practice, and a
   page with several forms is already safe: the store key is `(scope, key)` and scope
   defaults to the route id, so two forms sharing a page share a key only if they post
   to the same route — which is one form by construction of the view compiler.

5. **`Begin` reads header first, then the `_idempotency` field.** The same precedence
   `AuthStep.csrf` uses. `required: true` accepts either transport.

6. **Replay the headers that matter, by allowlist.** `complete` snapshots
   `HX-Trigger`, `HX-Redirect`, `HX-Retarget`, `HX-Reswap` and `Location` into a new
   `response_headers` column (JSON, small by construction); `Replay` re-emits them.
   The stored response is the negotiated one — a replay from a differently-shaped
   caller (htmx first, no-JS second) replays the first shape; the receipt-page answer
   is still reachable through it, and the mixture is not a flow any page produces.
   Schema change = a new versioned migration under `operations/` plus the oracle and
   sqlserver mirrors — never an edit to `V1` (Flyway checksums) — and the store's
   `ensureSchema` list gains the file for the vendors Flyway does not cover.

7. **Expired rows are swept, not immortal.** Today a key never re-presented lives
   forever (`begin` only reuses an expired row in place). `RetentionSweeper` gains a
   `where expires_at < now` prune with a `Result` count; no new config key — TTL is
   already per-record policy, so the sweep needs no retention duration of its own.

8. **Scaffolds and governance stay as they are, for now.** `RouteGovernance` already
   nudges write routes toward `idempotency:`; whether scaffolded command routes should
   declare it by default is a separate decision once the bridge has soaked. The queue
   consumer's `QueueDedupProcessor` and workflow transitions keep their own dedup
   contracts — this design touches the HTTP surface only.

## Slices

1. **Semantics** — `release` on the store and the edge's failure path; the form-aware,
   principal-folded request hash; the 4221/4090 split. Testcontainers ITs on the
   runtime module (validation-failure-then-resubmit; different-payload; in-flight).
   Benefits the header API before any form carries a key.
2. **The form bridge** — `_idempotency` minting, the hidden field, the reserved-field
   exclusion, the field fallback in `Begin`/`Complete`, `required` honoring either
   transport. `transactional-writes.md` and `hypermedia-ui.md` updated: the
   network-retry section gains its "pairs with the key" sentence with the mechanism
   now real.
3. **Replay fidelity + retention** — the `response_headers` column and migrations, the
   allowlist snapshot/re-emit, the `RetentionSweeper` prune.

Slice 1 ships alone and is a fix; slices 2 and 3 are the recipe. No YAML surface
changes in any slice — `idempotency:` already exists, so no schema, scaffold copies, or
`reference-yaml-surface.md` regeneration are touched unless slice 2 decides `required`
needs a transport note in the schema description (regen follows if so).

## Out of scope

- An `Idempotency-Key` response header or client-visible echo — nothing consumes it.
- Hashing uploaded file bytes — excluded above, revisit with a use case.
- Scaffold-default `idempotency:` on command routes — after the bridge soaks.
- Cross-node replay of streamed (`InputStream`) bodies — command responses are
  fragments and redirects; streams stay out of the store as they are today.
