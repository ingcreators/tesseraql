# Environments and promotion

The git-native recipe that aligns the read-only-prod-Studio posture with an explicit path
for how an edit gets to production. Nothing here is new machinery — it composes the pieces
the framework already ships.

## The loop

1. **Edit in dev.** Studio is writable in dev (`tesseraql.studio.readOnly: false`): route
   form, source editor, scaffolds, migrations, policy edits — everything lands as files in
   the app tree, and applying serves immediately (the hot reload).
2. **Branch and PR.** The app tree is a git repository; Studio edits are ordinary diffs.
   Open a PR from the dev branch. The Studio audit trail (`work/studio/audit/`) tells you
   who changed what if the diff needs context — `work/` itself is never committed.
3. **CI governance gate.** The PR pipeline runs the machine checks the framework provides:
   - `tesseraql lint` (or `tesseraql:lint`) — structure, security, references; findings carry
     `source:line`.
   - `tesseraql test` + coverage kinds (`tesseraql:report`) — the declarative suites, with
     per-kind thresholds.
   - `tesseraql release-diff --app . --baseline <deployed-tree>` — **what does this deploy
     change**: routes, API contract, the migrations it will run, policy changes, schema
     delta. Post the Markdown to the PR; a reviewer approves the *change*, not a prose
     description of it.
   - `tesseraql:release-evidence` — the SBOM, OpenAPI, and htmx-contract artifacts.
4. **Tag and package.** On merge, CI builds the immutable artifact — `tesseraql package`
   (`.tqlapp`) or the container image — stamped with the tag. Run artifacts under
   `.tesseraql/` stay out of the package by design; `spec.json` (byte-stable) rides along.
5. **Promote by config, not edits.** Staging and prod run the SAME artifact with a different
   environment profile: `--env staging` / `--env prod` selects `config/env/<profile>.yml`
   (datasources, pool sizing, metrics/audit switches). Secrets stay in the environment or
   the secret provider — never in the tree.
6. **Prod Studio is read-only.** `tesseraql.studio.readOnly: true` (optionally plus
   `editRoles: []`) in the prod profile: the explorer, docs portal, ops console and release
   diff page stay available for inspection; every write path is disabled. An edit gets to
   prod exactly one way — back through steps 1-5.
7. **Capture the next baseline.** After the deploy, capture the baselines the portal diffs
   against: copy `.tesseraql/docs/openapi.json` → `openapi.baseline.json` and
   `schema.json` → `schema.baseline.json` (and keep the deployed tree/tag available as the
   `release-diff` baseline for the next cycle).

## Rollback

Redeploy the previous tag with the same profile. Migrations are fix-forward (Flyway free
edition has no undo): roll back code freely, roll schema forward with a follow-up migration.
