# The documentation site — tutorial, cookbook, generated reference

Status: design v2 accepted 2026-07-04, superseding v1 (#274) before implementation
started. v1 proposed a hand-rolled Java static-site generator; v2 replaces it with
**Astro Starlight on Cloudflare**, mirroring the deployment that already serves the
Hypermedia Components documentation from the same organization. This is the
documentation-site leg of roadmap Phase 35; Maven Central, the Gradle plugin, and
official images are that phase's other legs and remain open.

TesseraQL's documentation already exists and is good: ~40 cookbook and design documents
under `docs/`, a deep JSON Schema of the whole YAML surface, and an error taxonomy with
300+ `TQL-*` codes living next to the code that raises them. What is missing is the
front door: one place to read it, navigate it, search it — and reference pages that are
**generated from those machine-readable sources instead of hand-maintained**.

## Decisions

- **Astro Starlight, not a hand-rolled generator.** A documentation framework is
  exactly the kind of infrastructure this project buys off the shelf rather than
  builds: Starlight ships full-text search (Pagefind), Shiki syntax highlighting
  (SQL/YAML/Java — most of what our pages are made of), dark mode, mobile navigation,
  per-page tables of contents, and prev/next links. The v1 Java generator would have
  reimplemented all of that badly. The site lives in `docs-site/` as a standalone
  pnpm project; the Maven reactor does not depend on it and needs no Node.
- **Mirror `hypermedia-components/apps/docs`.** Same framework (Astro + Starlight),
  same build-time link validation (`starlight-links-validator`), same
  `starlight-llms-txt` output (`/llms.txt` for AI coding agents — the audience the
  Studio copilot already serves), same canonical-URL shape: `site:
  https://ingcreators.com`, `base: /tesseraql`. One organization, one documentation
  stack.
- **`docs/` stays canonical.** The markdown tree keeps its role as the GitHub-browsable
  source of truth (README, PRs, and CHANGELOG all deep-link into it). A small sync step
  (`docs-site/scripts/sync-content.mjs`, run by `predev`/`prebuild`) copies mapped
  documents into Starlight's content directory, deriving the frontmatter `title` from
  each document's H1 and rewriting same-tree `*.md` links to site URLs. Authors never
  touch `docs-site/src/content/` by hand; the synced output is gitignored.
- **Curated navigation with a completeness guard.** One navigation manifest
  (`docs-site/nav.mjs`) drives both the Starlight sidebar and the sync step: every
  `docs/*.md` is mapped into a section (Tutorial / Building applications / Platform
  services / Security & identity / Operations / Project & design) or excluded
  explicitly (internal trackers), and the sync fails the build when a new document is
  neither — a doc can never silently miss the site. `starlight-links-validator` then
  fails the build on broken internal links or anchors over the rendered route graph.

## The generated reference

The reference pages are **generated as markdown into `docs/` and committed**, not
rendered at site build time. A build-only Maven module walks the machine-readable
sources and emits:

- **The YAML surface** from `schema/tesseraql-v1.schema.json` (the same schema the
  editors use, already drift-guarded against the linter by `SchemaSyncTest`): every
  property with its type, constraints, allowed values, and description.
- **The error-code index**, scanned from the main source trees: every
  `TQL-<DOMAIN>-<n>` — both the literal form and the `TqlDomain.<D>, <n>` constructor
  form — grouped by domain, with the raising files as provenance and links to the
  cookbook pages that mention each code. Honest about coverage: an undocumented code
  still appears — that is the point of an index.

Committing the output keeps the reference GitHub-browsable like every other document,
makes reference changes reviewable in diffs, and means the Cloudflare build needs no
JVM. A drift test in the module (the `SchemaSyncTest` pattern) fails CI when the
committed files no longer match what the sources would generate.

## Publishing

Cloudflare **Workers Static Assets** via git-connected Workers Builds, exactly like
Hypermedia Components: `wrangler.jsonc` at the repo root points the assets binding at
`docs-site/dist/`, and a small `worker.mjs` strips the `/tesseraql` base path (Static
Assets `_redirects` cannot express 200-rewrites). Production deploys track `main`;
non-production branches get preview versions (`npx wrangler versions upload`) — every
PR that touches the docs gets a URL. The CI `docs-site` job runs the same build
command as Cloudflare, so green CI implies a clean deploy.

Dashboard-side setup (create the Worker from the repo, attach
`tesseraql.ingcreators.com` as the fallback domain, add the
`ingcreators.com/tesseraql/*` zone route) is a one-time operator step documented in
`docs-site/DEPLOYMENT.md`, runbook-style, mirroring the Hypermedia Components
`DEPLOYMENT.md`.

## Out of scope (documented, not implied)

- A Japanese locale: the Starlight config keeps the door open (the Hypermedia
  Components site runs `root` + `ja` locales with fallback), but repo artifacts are
  English and the site launches English-only.
- Versioned doc snapshots per release; PDF export; comments/analytics.
- The other Phase 35 legs: Maven Central publication, the Gradle plugin, official
  images.
