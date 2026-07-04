# Deployment

The operational runbook for the public documentation site: the manual steps performed
in the Cloudflare dashboard, so a fresh maintainer can re-provision (or audit) the
deployment without archaeology. The design context is
[docs/docs-site.md](../docs/docs-site.md); the whole setup mirrors the Hypermedia
Components docs deployment (`ingcreators/hypermedia-components` → `DEPLOYMENT.md`).

> **Terminology.** Cloudflare has unified the old "Pages" product into Workers via
> **Workers Static Assets**. This runbook describes the unified Workers flow.

## What ships where

| Surface | Source | Build output | Host |
| --- | --- | --- | --- |
| Docs site | `docs-site/` (Astro Starlight) | `docs-site/dist/` | Cloudflare Workers (Static Assets) |

Content comes from the repository's `docs/` tree (synced at build time by
`scripts/sync-content.mjs`); nothing else in the reactor is involved — the Cloudflare
build needs Node only, no JVM.

## Repo-side artifacts

| File | Purpose |
| --- | --- |
| [`wrangler.jsonc`](../wrangler.jsonc) | Worker config at the repo root. Points the Static Assets binding at `docs-site/dist` and wires `worker.mjs` as the entrypoint. |
| [`worker.mjs`](../worker.mjs) | Strips the `/tesseraql` base path from incoming URLs and forwards to `env.ASSETS.fetch()`. Bare `/` redirects to `/tesseraql/`. |
| [`docs-site/public/_headers`](public/_headers) | Long-cache for fingerprinted `_astro/*` assets, revalidate for HTML, baseline security headers. Astro copies this into the build root. |

The base-path stripping lives in JS because Workers Static Assets `_redirects` does
not support `200` (rewrite) status codes — only true 30x redirects.

## Cloudflare Workers — initial setup (one-time)

### Step 1 — Create the Worker

Cloudflare dashboard → **Workers & Pages → Create → Import a repository (Workers
Builds)**: authorize the Cloudflare GitHub app on `ingcreators/tesseraql`, production
branch `main`.

### Step 2 — "Set up your application" form

| Field | Value |
| --- | --- |
| Project name | `tesseraql-docs` |
| Build command | `cd docs-site && corepack enable && pnpm install --frozen-lockfile && pnpm run build` |
| Deploy command | `npx wrangler deploy` |
| Builds for non-production branches | ☑ checked |
| **Advanced — Non-production branch deploy command** | `npx wrangler versions upload` |
| **Advanced — Path** | *(leave empty / `/`)* — `wrangler.jsonc` lives at the repo root |
| API token | **+ Create new token**; accept the default scoped token |

Why these values:

- The build command is the one CI also runs (`.github/workflows/ci.yml`, `docs-site`
  job), so green CI implies a clean Cloudflare build. `corepack enable` activates the
  pnpm version pinned in `docs-site/package.json` (`packageManager`).
- `npx wrangler deploy` reads `wrangler.jsonc` and uploads `worker.mjs` plus
  `docs-site/dist/` as Static Assets.
- `npx wrangler versions upload` creates an un-promoted preview version — PR previews.

### Step 3 — First deploy, smoke checks

The site lands at `https://tesseraql-docs.<account>.workers.dev/`. Verify:

- [ ] `/` redirects to `/tesseraql/` (301) and the landing page renders.
- [ ] Sidebar entries link to working pages; a deliberate `/tesseraql/no-such-page`
      shows the Starlight 404, not Cloudflare's default.
- [ ] `_astro/*` assets load; search opens (Pagefind; `Ctrl+K`); SQL/YAML code blocks
      are highlighted.
- [ ] `/tesseraql/llms.txt` serves.

### Step 4 — Attach the subdomain (operational fallback)

Worker → **Settings → Domains & Routes → Add → Custom domain**:
`tesseraql.ingcreators.com`. Cloudflare manages DNS automatically; repeat the smoke
checks there.

### Step 5 — Worker route on the canonical path

The canonical URL in the Astro config is `https://ingcreators.com/tesseraql/`. Attach
a Worker Route in the `ingcreators.com` zone (defer until the rest is stable):

```text
Zone:   ingcreators.com
Route:  ingcreators.com/tesseraql/*
Worker: tesseraql-docs
```

No second Worker is needed — the existing one already understands the `/tesseraql/`
prefix.

## Routine operation

Nothing manual: pushes to `main` build and deploy; PR branches build preview
versions. If a deploy misbehaves, check the Worker build logs in the dashboard, then
`.github/workflows/ci.yml` (`docs-site` job) for the same failure reproduced in CI.
