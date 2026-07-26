# Studio Schema Lifecycle

Design document. Studio's documentation and authoring surfaces read build-time sidecars
under `.tesseraql/docs/` — `schema.json` feeds the SQL builder's column dropdowns, the
migration DDL builder, the docs schema/table pages, search, and the copilot's
`schema_tables` tool; `schema.baseline.json` and `openapi.baseline.json` feed schema-diff
migration generation and the release diff. Today every one of those files is produced or
captured **outside** Studio: `schema.json` by the `tesseraql:schema` goal or `tesseraql
schema` CLI, the baselines by hand-copying files in a shell. Three consequences:

1. A stale or absent `schema.json` silently degrades the builders — dropdowns go empty,
   generated SQL carries `/* TODO */` placeholders — while Scaffold, which introspects
   live, keeps seeing the real database. Two sources of truth, one of them quietly wrong.
2. Schema-diff migrations and the API changelog are **off by default**: they require a
   manual copy step most apps never perform.
3. A corrupt sidecar reads exactly like an absent one (`DocService.schema()` returns
   `null` for both), so the empty state lies about the cause.

This document closes the lifecycle inside Studio with two actions and one diagnostic.

## Decisions

### 1. "Refresh schema" introspects live, through the runtime's own pools

A new `studio.schemaRefresh` provider walks the runtime's datasource map (the same
`CatalogIntrospector` Scaffold already uses live, one short-lived read-only
JDBC-metadata connection per datasource) and writes `.tesseraql/docs/schema.json` in the
`SchemaOverlay` envelope (`schemaVersion: 1`, `generatedAt`, `datasources`) — the same
wire shape `SchemaGenerator` produces at build time; the report module is not added as a
runtime dependency for a ten-line envelope. The button sits on the docs schema page's
empty state and header, replacing the "run the `tesseraql:schema` goal" instruction as
the first resort (the goal remains the CI path).

### 2. "Capture baseline" writes both baselines in one action

A `studio.baselineCapture` provider writes `schema.baseline.json` (a copy of the current
`schema.json` — refuse with a clear message when there is none) and
`openapi.baseline.json` (from `DocService.openApiJson()`, which the runtime always
generates live from the manifest — no intermediate `openapi.json` file exists at runtime,
and release-diff.html's current instruction to copy one is a documentation bug this
slice deletes). One button, both diffs armed. It sits on the migration page and the
release-diff page where the copy instructions sit today.

### 3. Same write discipline as every Studio write

Both providers: `studioAccess.requireEdit(roles)`, paths through `StudioService`'s
confinement (`resolve()` — `.tesseraql/docs/` is inside the app home), audit entries
(`schema-refresh` / `baseline-capture` actions), actor from `principal.loginId`. Plain
POST routes + 303 + flash, the established shape. `.tesseraql/` is already excluded from
`.tqlapp` packaging, so neither action affects artifact reproducibility.

### 4. Corrupt reads become visible

`DocService` distinguishes absent from unparseable: the schema page's empty state names
the actual problem ("schema.json exists but cannot be parsed — refresh to regenerate")
instead of pretending nothing was ever introspected.

## Out of scope

- `report.json` / `history.json`: test-run artifacts; they belong to the test runner and
  the `report` goal, not to a schema action.
- Automatic baseline capture on release tags (a CI concern; the button makes the manual
  step one click, CI can keep copying files).
- Multi-datasource selection UI: refresh introspects every pool it finds, matching the
  build-time generator's whole-map envelope.

## Testing

- StudioIntegrationTest: refresh populates the schema page and the SQL builder dropdowns
  without a restart; capture arms both the migration diff and the release diff; both
  actions 403 for a read-only viewer; both audit rows appear; a corrupt sidecar renders
  the corrupt-state message and refresh replaces it.
