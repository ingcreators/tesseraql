# Documentation style guide

The rules this documentation is written to. They exist because an audit measured the corpus
and found it accurate but hard to read: sentences averaged 25.8 words against a 15–20 norm for
technical documentation, 333 of 2,287 sentences ran past 40 words, and em dashes appeared once
every two and a half sentences.

Most of these are held by `docs-site/scripts/lint-prose.mjs`, which reports on the corpus and
fails the build on the hard limits.

## Sentences

**Aim for 20 words. Do not exceed 40.** A 40-word sentence is not wrong, it is simply harder
than it needs to be, and this documentation has readers whose first language is not English.

**One clause stack per sentence.** The house habit is to keep appending qualifications with em
dashes and parentheses until a sentence carries four ideas. Split it. Two plain sentences beat
one intricate one.

**Two em dashes per paragraph, at most.** An em dash is for a genuine aside. When it is doing
the work of a full stop, use a full stop.

Before:

> A `pagination:` block on a `query-json`/`query-html` route paginates the main query — the
> framework appends the dialect's pagination clause at execution time, so the authored 2-way
> SQL stays plain-tool runnable and carries no `LIMIT`.

After:

> A `pagination:` block paginates a query route's main query. The framework appends the
> dialect's pagination clause at execution time, so the authored SQL carries no `LIMIT` and
> still runs in a plain SQL tool.

## Openings

**Every page opens by saying who it is for and what it lets them do**, in two or three
sentences, before any mechanism. A reader who is on the wrong page should be able to tell
within one paragraph.

**Do not open with a definition of the feature's internals.** "A view is a `kind: view`
document that describes a page" is the second sentence, not the first.

## Structure

**Every page ends with a `## Next` section** naming two or three pages that follow, each with
a gloss saying why. The sync guard enforces this. Generated reference pages are exempt.

**Prefer a table to a list of parallel prose.** Options, keys, columns, and policies are
tables.

**Headings are sentence case** and describe the task, not the machinery: "Watching a run"
before "The execution model".

## Words

Product surfaces have one spelling each:

| Use | Not |
| --- | --- |
| Studio | the Studio, TesseraQL Studio |
| the ops console | the operations console, Ops Console |
| IAM Admin | IAM admin, the IAM console |
| 2-way SQL | two-way SQL |
| application (in prose) | app, except in `.tqlapp` and directory names |
| sign in / sign out | log in / log out |

**Say "you" for the reader and name the actor otherwise.** "The framework appends the clause",
not "the clause is appended".

**Do not use roadmap or planning vocabulary on a published page** — no phases, milestones, or
slice numbers. The sync guard already fails the build on these.

## Honesty

**Say what a feature does not do.** The audit found that pages describing capabilities without
their limits leave readers to discover the limits in production.

**Name the configuration key.** "Enable the data browser" is less useful than
"`tesseraql.studio.dataBrowser.enabled`".

**Link rather than repeat.** A guide that starts explaining a feature has become a second copy
of that feature's page, and the copy will rot.

## Next

- [documentation-portal.md](documentation-portal.md) — the per-application reference the
  framework generates.
- [concepts.md](concepts.md) — the vocabulary these rules assume.
