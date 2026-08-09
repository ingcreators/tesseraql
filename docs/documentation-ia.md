# Documentation information architecture

Status: design accepted 2026-08-09. An audit of the published documentation — 50 pages,
~103,000 words, plus 45 internal documents — found the prose sound and the tooling solid,
and the *structure* unbuilt: pages were added one-per-feature onto a flat list, and no
reading path was ever designed. This document records what the audit measured, the
information architecture that replaces the current one, and the slices that get there.

## What the audit found

The site's machinery is in good order and is not in question: `nav.mjs` is a real
manifest with a completeness guard, links are validated at build time, `/llms.txt` is
published, headings are sentence-case in 393 of 395 places, and every page opens by
saying what its feature is for. The defects are architectural.

**The three consoles have no page.** Studio is 37 screens and 110 route documents; the
ops console is 11; IAM Admin is 10. None of them has a page on the site. Studio is
mentioned in 30 published documents and over 100 times in total, and the document that
says the most about it is `vscode-extension.md`. A reader who follows the five-minute
demo to "the ops console's jobs page" has nowhere to go and learn what that console is.
The material exists — `ops-console-actions.md`, `ops-console-coverage.md`,
`studio-ux-refresh.md`, `session-administration.md` — as design documents that were
never promoted into user pages. That is the root cause, and it recurs: four published
pages link *out* to internal design documents because the user-facing version was never
written.

**The tutorial ladder is broken in two places.** The site's primary call to action leads
to `getting-started.md`, whose "Next" section points at `app-layout`, `deployment`, and
`proxy` — never at `your-first-app.md`, the actual tutorial. The five-minute demo ends
without a next step. `your-first-app.md` therefore has no inbound link from any published
page; nor do `identifiers.md` and `upgrading.md`.

**There is no conceptual layer.** No overview, concepts, architecture, glossary, FAQ, or
troubleshooting page exists. How `route`, `recipe`, `view`, `job`, `workflow`, `scope`,
`domain`, and `decision` relate is assembled by the reader from 21 feature pages, or not
at all.

**Reference pages have holes at the centre.** Of the 195 properties in
`tesseraql-v1.schema.json`, 93 carry a description — so 102 rows of the generated YAML
reference read `—`, including `security`, `sql`, `steps`, `response`, `columns`,
`fields`, and `panels`. The same schema drives editor intellisense in the VS Code
extension and Studio, so each missing description is missing in three places. Two
pointers inside the shipped schema are wrong: `input-binding.md` has never existed, and
the root description locates documents under a `routes/` tree when the directory is
`web/`. There is no CLI reference at all — 27 subcommands are scattered through prose —
and no configuration-key reference.

**Two maintainer documents sit in a user section.** `security-hardening.md` states in its
own opening that it is a maintainer document; `threat-model.md` is framework-level. They
occupy two of the nine pages a reader browses when looking for how to secure their own
application.

**Navigation is sidebar-only.** Seven of 50 pages offer a next step. "Building
applications" is 21 pages flat, with `app-layout` and `duckdb` at equal weight.
"Operations" holds four pages while `deployment.md` carries eleven H2 sections and is the
second most-linked page in the corpus.

**The prose is dense.** Sentences average 25.8 words against a 15–20 norm for technical
documentation; 333 of 2,287 sentences exceed 40 words; em dashes run 19.7 per thousand
words. The heaviest pages are `analytics` (35.3 words per sentence), `duckdb` (32.1),
`notifications` (31.6), and `jobs` (30.6, with 23 sentences over 40 words). Surface names
drift: `ops console` (23), `operations console` (12), `Ops Console` (2); `IAM Admin`
(20), `IAM admin` (8).

## Decisions

**Sections carry reader intent, not feature taxonomy.** The current six sections name
parts of the framework. The replacement names what a reader is trying to do, which is the
only axis on which a newcomer can choose. Seven sections:

| Section | Holds |
| --- | --- |
| Start here | overview, getting started, the five-minute demo, the first app |
| Guides by use case | one page per application shape, routing to existing pages |
| Concepts | the mental model, application layout, 2-way SQL, identifiers, glossary |
| Building applications | the feature pages, ordered essential-first |
| Consoles and tools | Studio, ops console, IAM Admin, the CLI, the VS Code extension |
| Running in production | deployment split by operator task |
| Reference | generated surfaces, CLI, configuration, error codes, troubleshooting |
| Evaluating TesseraQL | admission, the ASVS self-assessment, the threat model |

**The consoles become first-class pages.** `studio.md`, `ops-console.md`, and
`iam-admin.md` are written as user pages — who opens this, when, and to do what — with
the existing design documents as source material, not as text to move. The design
documents stay internal and stay accurate; the user pages are new writing.

**Use-case guides route, they do not duplicate.** Each guide is a short path through
pages that already exist: what to read, in what order, and what the finished shape looks
like. The four shapes the gallery applications already demonstrate — approval workflow,
integration and batch, analytics and reporting, an API over an existing database — are
the four guides. A guide that starts explaining a feature has failed; it links instead.

**Every page ends somewhere.** A "Next" or "Related pages" section becomes a convention
held by the sync guard, the same way internal vocabulary already is. A page with no
outbound path is a dead end, and the build says so.

**Schema descriptions are documentation.** Filling `description` in the JSON schema is
the highest-leverage work in this campaign: one edit improves the generated reference,
the extension's hover text, and Studio's editor at once. The generator gains a coverage
report, and a test floors the percentage so it cannot regress.

**Generated where generable.** The CLI reference is generated from the Picocli command
model the way the YAML reference is generated from the schema, so it cannot drift from
the binary. The configuration reference is generated from the scaffolded-key registry
that `ScaffoldedConfigKeys` already holds.

**A style guide with measured limits.** Sentence length, em-dash density, and surface
naming get written rules and a lint that reports on the corpus. Existing pages are
rewritten worst-first, not all at once.

## Slices

1. **The consoles.** `studio.md`, `ops-console.md`, `iam-admin.md`; the "Consoles and
   tools" section; every incidental console mention repointed at them.
2. **The ladder and the dead ends.** Repair getting-started → your-first-app → the guides;
   add "Next" to every page; the sync guard that keeps it true.
3. **Schema descriptions and the broken pointers.** Fill the 102 gaps, fix
   `input-binding.md` and `routes/`, add the coverage floor test, regenerate.
4. **The CLI and configuration references.** Two generators, two committed pages, drift
   tests.
5. **Concepts and use-case guides.** `overview.md`, `concepts.md`, `glossary.md`, and the
   four guides; the section reshuffle lands here.
6. **Personas.** The "Evaluating TesseraQL" section; a README rewritten for the reader who
   arrives from GitHub, linking the site.
7. **Prose.** The style guide, the corpus lint, and the worst-first rewrite.

## Out of scope

- A Japanese locale. Repository artifacts stay English; the site stays English-only.
- Versioned documentation snapshots per release.
- Rewriting every page's prose. Slice 7 rewrites the measured worst and sets the rule;
  the rest converge as pages are touched.
- Moving `docs/` out of the repository, or changing the sync and publishing machinery,
  which the audit found sound.
