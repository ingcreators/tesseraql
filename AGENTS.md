# Coding Agent Instructions

These instructions apply to Codex, Claude Code, and other coding agents working on TesseraQL.

## Project goals

TesseraQL is a SQL-first hypermedia and integration framework that compiles simple external YAML into Apache Camel routes, executes SQL through TesseraQL custom Camel components, renders JSON/HTML responses, and provides tests, coverage, operations, and Studio tooling.

## Current baseline

- Java baseline: 21
- Java compatibility target: 25
- Build tool: Maven
- Runtime direction: Apache Camel based
- User-facing DSL: TesseraQL Simple YAML
- SQL style: SQL-tool-compatible 2-way SQL
- UI component system: Hypermedia Components
- Dev environment: Dev Container

## Mandatory rules

1. Do not edit generated files directly. Edit source YAML/Java/templates instead.
2. Keep `tesseraql-core` free from Camel, Spring, and CLI dependencies unless explicitly intended.
3. Do not introduce runtime dependencies in core modules without explaining the module boundary.
4. Keep user-facing YAML simple; avoid leaking raw Camel DSL into standard examples.
5. SQL examples should remain executable in normal SQL tools by using TesseraQL 2-way SQL comments and dummy values.
6. Never commit secrets, tokens, local `.env` files, agent auth files, or real credentials.
7. Prefer small, reviewable commits and include tests for behavior changes.
8. Run `mvn -B -ntp verify` before proposing a final change when feasible.
9. Land changes through pull requests with CI green; never push directly to `main`.
10. Until v1.0.0, backward compatibility is not a goal. Prefer the cleanest design over
    compatibility shims, aliases, or deprecation layers; delete replaced code outright and
    record breaking changes in the CHANGELOG.
11. If a gap belongs to Hypermedia Components (a missing component, token, behavior, or
    markup contract), do not paper over it in TesseraQL — no custom CSS over `hc-*`
    internals, no hand-rolled behaviors, no invented markup. Write an improvement brief
    against the upstream library and adopt the released feature; `tesseraql.css` keeps only
    genuinely app-specific rules. UI work follows the blessed patterns in
    `docs/hypermedia-ui.md`; emitted hc markup (class names, data attributes) is a public
    contract of the route compiler.
12. Repository artifacts — code, comments, docs, commit messages, PR text — are written in
    English.

## Suggested workflow

```bash
mvn -B -ntp verify
mvn -B -ntp -pl tesseraql-core -am test
```

When piping Maven output (e.g. through `tail`), the pipeline's exit code is the last
command's, not Maven's — confirm `BUILD SUCCESS` in the log or check `${PIPESTATUS[0]}`
before treating a build as green.

## Naming

Use TesseraQL consistently.

Correct examples:

- `TesseraQL`
- `tesseraql-*`
- `version: tesseraql/v1`
- `/ _tesseraql` without the space when used as an actual route prefix

Avoid legacy names such as SQLBase or sqlbase in source artifacts unless discussing historical migration.
