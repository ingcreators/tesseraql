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

## Suggested workflow

```bash
mvn -B -ntp verify
mvn -B -ntp -pl tesseraql-core -am test
```

## Naming

Use TesseraQL consistently.

Correct examples:

- `TesseraQL`
- `tesseraql-*`
- `version: tesseraql/v1`
- `/ _tesseraql` without the space when used as an actual route prefix

Avoid legacy names such as SQLBase or sqlbase in source artifacts unless discussing historical migration.
