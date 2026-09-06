# Base-path URL emission

> **Status: complete.** Eight slices shipped 2026-09-06 (#1199-#1206), closing the four findings
> the 2026-09-04 whole-repo audit filed against base-path emission (F28, F29, F30, F32) and four
> more that re-measurement and the review of it found around them.
>
> In the order they landed: this design (#1199), the document key as a path segment (#1200), the
> prefixed crawling guard (#1201), the lookup field's legs (#1202), `_return` at its readers
> (#1203), the error page's `base` (#1204), the hot-reload prefix (#1205), and the reviewed
> import (#1206). Slice 6 ran ahead of slice 5 because its red test was already in hand and
> nothing coupled them.
>
> **The plan this document replaces was re-measured against `ecdd5d6c8` and was wrong in
> seventeen places**, and building it found more. Two corrections are load-bearing enough to
> restate here. The reviewer's five-line `ViewBinding.basePath` field is not equivalent to
> threading an address through — but neither was needed, because the fix belongs at the reader
> and not the emitter at all
> ([decision 5](#5--a-return-is-returned-to-base-relative-form-where-it-is-read-back)). And the
> plan's two extra `Templates.render` sites are TEXT-mode renderers, not missed page emissions.
> Read the decisions below, not the plan.
>
> **The premise the whole campaign was ranked under was also wrong, in the project's favour.**
> [`base-path.md`](base-path.md) presents a base path as something an operator opts into. It has
> not been optional since 2026-08-17. The next section is that finding, because every priority
> call in this document follows from it.
>
> **What is left is filed, not forgotten**, in
> [Filed, not fixed](#filed-not-fixed): the ejector's form action and row link, the third
> `_return` consumer, and a lint whose trigger is inverted with respect to decision 12.

## A base path is not opt-in, and has not been since 2026-08-17

[`base-path.md`](base-path.md) decision 1 describes two deployment shapes:

- **A** — one standalone runtime, its prefix chosen by the operator in configuration, behind a
  reverse proxy at `/myapp`.
- **B** — stack mode: a gateway at the origin root, each member addressed at `/<name>`, the prefix
  *derived* by the host and *injected* into the member's configuration.

**Shape A no longer exists.** `tesseraql serve` was the gateway-less single-application shape, and
[`stack-architecture.md`](stack-architecture.md) decision 12 removed it. The commit that carried
that out (#846, `96b03c5bb`, 2026-08-17) says so in its own message — "serve was the gateway-less
single-application shape - the second deployment topology stack-architecture Decision 12 removed".

That same commit changed `base-path.md` by exactly one line:

```diff
-alongside it by whatever starts the runtime**. A standalone `serve` uses the base path; a
+alongside it by whatever starts the runtime**. A standalone runtime start uses the base path; a
```

The verb was renamed out of the sentence and the premise was left standing. Nothing has touched
the file in the twenty days since. Its decision 4 still cites "an isolated-mode gateway", a mode
decision 12 also deleted.

Measured at `ecdd5d6c8`:

| Question | Answer |
| --- | --- |
| Boot verbs | `dev` and `host`, both through `MultiAppGateway` |
| Main-source callers of `TesseraqlRuntime.start` | `MultiAppHost:306` (surface, prefix `""`) and `:525` (member) |
| Where a member's address comes from | `InstalledApp.basePath()` — `return "/" + name;`, the one producer |
| A declared address | Refused by the `@JsonCreator`: "addresses are not declarable" |
| An app that sets `tesseraql.http.basePath` itself | Overwritten, pinned by `MultiAppHostIntegrationTest.theDerivedAddressOutranksTheApplicationsOwnBasePath` |
| Shipped launchers | `Dockerfile` `host --stack`; `Dockerfile.demo` `dev --stack`; the Windows service unit `host --stack` |

Shape A survives only as an unmarked Java embedding surface — `TesseraqlRuntime.start(Path)` and
four overloads, javadoc'd "used by tests", one of them still naming the deleted `serve` verb. No
shipped module, example, pom or documentation page uses them, and the repository declares no
stability annotations, so nothing about them is promised.

**What follows from this, and it inverts the campaign's priority.** A non-empty prefix used to be
opt-in, which made every prefix-dependent emission defect latent for anyone not running behind a
proxy. Every user application in every deployment now serves at `/<name>`, and there is no shipped
configuration that yields an empty prefix for one. So the four defects below are not
reverse-proxy edge cases. They break the default first-run path: `tesseraql dev` over `examples/`,
and `docker run` of the published image alike.

The one runtime that still sees `""` is the stack **surface** — the origin portal and the system
applications mounted beside it. That is the reverse of the assumption the emission code was
written under, and it is why the exemption list in
[decision 2](#2--two-lists-keyed-by-mechanism-and-the-_system-hrefs-are-on-the-wire-side) is
about the framework's own surfaces rather than about applications.

## What is broken

Every row was re-confirmed at `ecdd5d6c8`. The first four are the audit's; the rest were found by
re-measuring the plan and by the review of that re-measurement.

| # | Where | What happens |
| --- | --- | --- |
| F28 | `field.html:34/:40/:48`, `lookup-dialog.html:16/:19/:35` | The lookup field's four legs emit `${f.lookup.resolve}` raw. The value is base-relative by construction — `ViewBinding:643-644` builds it from the same `action` that `form.html:27-28` wraps in `@{}` — so under a prefix the code input, the search button, the dialog and every pick row address the origin while the form's own submit still works. Nothing errors; the field simply never resolves. |
| F29 | `_return` consumers | `_return` is handed out as a wire URL, and the three places that read it back off the request pass it to the redirect helper, which prefixes what it is given. Measured: `/shop/shop/things?page=2`. The plan counted four consumers; there are three. |
| F30 | The import job page | Publishes no `base`, so the link builder has nothing to read and the no-JS confirm lands unstyled. |
| F32 | `ViewBinding:751` | A document key is form-encoded into a path segment by a plain `URLEncoder`, so a key with a space renders `/docs/PR+1/approve`. Vert.x decodes plus-as-space **off**, so the transition posts at a document that does not exist while the stepper above it shows the right one. |
| — | `ViewEjector:489-521` | The ejector hand-writes the same three lookup legs as root-absolute string literals. A template fix cannot reach them, and ejection is a one-way door: the author owns the output forever. |
| — | `FileImportProcessor:168-170` | The review page's `confirmAction` doubles a prefix that publishing `base` would otherwise fix. |
| — | `RouteReloader.reload()` | Re-reads the manifest from disk and discards the prefix the host injected in memory, so after any watched edit a hosted member's `base` becomes `""`. |
| — | `BasePathRules` | The root-absolute link lint returns early unless the application's own configuration declares a prefix — so it is silent for exactly the applications that will be served under one. |

The nearest existing guard, `StackModeIntegrationTest`, misses all of them: `examples/user-admin-app`
declares no `lookup:`, no `workflow:`, no `import:` and no `location: back`.

## Decisions

### 1 — The prefix is derived, single-producer and one segment, and emission may rely on it

`InstalledApp.basePath()` is the only thing that can mint an application's address, it is total,
and the segment-safety rule on `tesseraql.app.name` is what makes it total. So emission code is
written against a known shape rather than defensively against operator text:

- exactly one segment, never nested — `/a/b/c` cannot occur;
- never a trailing slash;
- never null and never absent for a user application.

The third is the one with teeth: **a missing `base` is a defect to assert on, not a state to
tolerate.** That is what turns F30 from a rendering nicety into a bug, and it is what the guard in
[decision 7](#7--the-guard-is-a-prefixed-boot-that-crawls-what-it-emits) asserts.

A name is legally non-ASCII, so an address can be `/受注管理`. It is percent-encoded once, when it
becomes a wire URL, and never again — the same rule as
[decision 8](#8--a-document-key-is-a-path-segment-and-a-path-segment-is-not-a-form-field), one
layer up.

### 2 — Two lists keyed by mechanism, and the `_system` hrefs are on the wire side

`base-path.md` decision 7 says a URL is base-relative inside the runtime and acquires the prefix on
its way out. That rule is correct and is not being changed. What it does not say is which URLs are
*inside* — and a sweep that wraps everything in `@{}` breaks the framework's own surfaces.

Two lists, keyed by the mechanism that produced the value, not by the file it lives in:

**Base-relative — must pass through the link builder.** Everything a route compiler or a view
binding derives from an application's own declared paths: form actions, list and detail links,
pager links, the lookup legs, `_return`, the import job card, transition actions.

**Wire by construction — must not be wrapped.** Values that were already wire URLs when they were
produced, and that acquire nothing:

- the `_system` hrefs — `consoleHref`, `studioHref` and `iamHref`. On a hosted member these point
  at the stack's origin fence, deliberately, because the ops console, Studio and IAM Admin are the
  stack's and are mounted once at the origin scope. `shell.html` emits them raw at nine `href`
  sites and one `data-value` site, and every one of those is correct.
- the **account surface** — `accountHref` and the pin toggle's `toggleHref`. Found by booting the
  guard's fixture rather than by reading, and on exactly the same rule: `ShellChrome` branches on
  `hostedMember()` and emits these origin-absolute because a member's account surface is the
  stack's. `logoutHref` sits beside them and is *not* on this list — sign-out stays the member's
  own route, so it goes through the link builder. Three adjacent values, two rules, and the code
  already says which is which.
- a URL read back off the request, which decision 7 already exempts;
- an absolute URL supplied by an identity provider;
- pins and recents, which the browser captured from its own location bar.

`_system` is the case neither the audit nor the plan covers, and it is the one a well-meant sweep
would break, so it is written down here before any sweep is authored.

One inconsistency is recorded and **not** fixed by this campaign: an origin-fence href carries the
application prefix with no activation segment, where a base-relative URL carries both. It is filed
in [open questions](#open-questions).

### 3 — `pagePath` stays a wire URL for now, and the deferral is deliberate

Making `pagePath` itself base-relative is what would allow a static "every emitted URL passes
through `@{}`" lint, because the remaining raw emissions would then all be exemptions. It also
decides whether pager links keep the activation segment.

It is **deferred**, and it is deferred rather than dropped. The reason is sequencing: the
behavioural defects above are live on the default deployment path today, and `pagePath` touches
the pager, the activation segment and the list surface at once. Ten by-design wire-URL sites
(`table.html:29`, nine in `list.html`, `job-card.html:22`) stay unchecked until it lands. That is
the cost of the deferral and it is accepted knowingly.

### 4 — The static link lint is not extended, and its trigger is inverted instead

The audit's own fix text asked for a static pattern guard over emitted attributes. It is refused,
for a measured reason: the value in `hx-get=${f.lookup.resolve}` is computed at request time, so no
regex over templates can see whether it was prefixed. A static guard would pass on the exact defect
this campaign exists to fix.

What *is* corrected is the existing lint's trigger. `BasePathRules` returns early when the
application declares no prefix of its own. Under decision 12 no application declares one and every
application gets one, so the lint is silent precisely where it is now needed. Its condition becomes
unconditional. This is a lint over an application's own markup and it stays a warning.

### 5 — A `_return` is returned to base-relative form where it is read back

The plan's reviewer proposed giving `ViewBinding` a `basePath` field with a setter, defaulting to
`""`, set at the three `RouteCompiler` construction sites — "same behaviour, same unit test, no
signature churn".

**It is not the same behaviour.** Child and panel embeds are separate `ViewBinding` instances
constructed inside `ViewBinding.of` itself, by an unqualified `of(...)`, never by `RouteCompiler`.
They reach the emitter carrying the host's page path. A field set only at the compiler's three
construction sites is empty on every one of them, so the change ships green, with its unit test
passing, while every embedded list inside a detail or dashboard page still emits a wire `_return`.

**Amended when the slice was built: the fix is at the reader, not the emitter, and neither shape
is needed.** Both this decision and the reviewer's alternative assumed `_return` had to be *emitted*
base-relative, which is what dragged in the `embed(...)` propagation problem. Measuring the
consumers settled it differently.

`RedirectRenderer.negotiate` states its own contract — "the location is base-relative and acquires
the application's prefix here… the one place the prefix has to go." So a caller handing it a wire
URL is the defect, and `_return` is read straight off the request. `BasePaths.relative` exists for
exactly this, and says so: "for the places that read a path back off the request and hand it to
something that will prefix it again, such as the login page's `next` target." The login target
already makes this move; `_return` did not.

So `_return` stays a wire URL end to end — emitted wire, echoed wire by the hidden input, posted
back wire — and each consumer returns it to base-relative form before the redirect helper. That is
one line per consumer, needs no `embed(...)` propagation, no `PageAddress` record and no
`ViewBinding` field, and is robust to an emission point that ever produces a base-relative value,
because `relative` leaves a URL that does not carry the prefix alone.

There are **three** such consumers, not the four the plan counted: `RedirectRenderer.resolveLocation`,
`WorkflowTransitionRenderer`, and `BulkReportRoundTrip`, which builds its `Location` from the same
wire value.

### 6 — An ejected page is a one-way door, so the ejector emits through the same rule

`ViewEjector` writes markup an author then owns. It cannot be corrected later by fixing a template,
and the framework's own root-absolute link lint would warn about a page the framework's own
generator produced. So the ejector is not a follow-up: it is in the same slice as the template it
mirrors, and `ViewEjectorTest`'s two assertions on the old literals are updated in that slice
rather than deleted.

### 7 — The guard is a prefixed boot that crawls what it emits

The deliverable is not four corrections; it is the check that would have caught all four and will
catch the fifth. One standalone boot with a prefix set, over a fixture that declares all four of
the surfaces the audit found broken, asserting three properties over every emitted URL attribute:

1. **prefixed exactly once** — catches F28 (never) and F29 (twice);
2. **no form encoding in a path segment** — catches F32;
3. **every crawled link resolves to a mounted route** — catches F30, and is the property that
   generalises, because it does not enumerate what to look at.

The fixture is new rather than dogfooded into `examples/user-admin-app`, because adding four
surfaces to a published example changes what that example teaches. The cheaper alternative is
recorded in [open questions](#open-questions).

**The guard lands over broken ground, so it carries a shrink-only ledger.** `KNOWN_UNPREFIXED`
names each emission that is not prefixed yet, with the slice that deletes it. A second assertion
fails when an entry stops being emitted unprefixed, so a fix cannot land without clearing its
line — which makes the ledger's shrinking the red test for every slice that follows. This is the
same shape the repository already uses for seeded drift ledgers.

**Two things a fixture must do that reading the code does not reveal**, both found by booting it:

- A hosted member fences every route behind `tql.app.use.<member>` (`AuthStep.fence`), and that
  step is a no-op on an unhosted boot. A fixture copied from a plain-boot test answers 403 on
  every page until its principal carries the grant.
- Three of the four surfaces fail *silently* — a workflow region with no row, a lookup companion
  with no form whose `action:` matches the POST route's path, a list with no `key:`. A guard over
  a fixture that renders nothing passes while checking nothing, so the fixture asserts that it
  rendered before anything asserts on what it rendered.

### 8 — A document key is a path segment, and a path segment is not a form field

`URLEncoder.encode` is `application/x-www-form-urlencoded`. In a path segment its space is wrong:
Vert.x decodes a path parameter with plus-as-space off, so the round trip does not close.
`BasePath.encodeSegment` already exists and is already what the activation segment uses. Every
place that builds a path segment from a value uses it.

This one is independent of the prefix — it is wrong at the origin root too — and it lands ahead of
the harness.

### 9 — The prefix is fixed for a runtime's lifetime, and a reload does not lose it

`base-path.md` lists per-request base paths as out of scope: "the prefix is fixed for a runtime's
lifetime." `RouteReloader.reload()` re-reads the manifest from disk and re-derives the prefix from
that file, which discards the value the host injected in memory. After any watched edit, a hosted
member's `base` is `""` while the edge still serves it prefixed.

This was filed by neither the audit nor the plan. It is in scope because it breaks a recorded
decision of the design this campaign is completing, and because `dev --watch` is how the framework
asks people to work.

## The slices

Each is one pull request, branched from fresh `origin/main`. Slices 4 through 8 extend the fixture
slice 3 introduces, so they are branched **sequentially**, never in parallel.

| # | Slice | Size | Closes |
| --- | --- | --- | --- |
| 1 | This design document, registered in both internal-doc lists | S | — |
| 2 | A document key is a path segment, not a form field | S | F32 |
| 3 | A prefixed boot crawls what it emits | M | the guard |
| 4 | The lookup field's legs go through the link builder, ejector included | S | F28 |
| 5 | The job page publishes `base`, and the card and confirm stop doubling | M | F30 |
| 6 | `_return` is returned to base-relative form where it is read back | S | F29 |
| 7 | A custom error page publishes `base` | S | — |
| 8 | A hot-reloaded member keeps its injected prefix | M | — |

Slice 2 lands before slice 3 because it needs no prefix and collides with nothing, which makes it
the campaign's cheapest red test.

## The guards

- `BasePathEmissionIntegrationTest` — decision 7's crawling boot. The campaign's real deliverable.
- `BasePathRules`, trigger inverted — decision 4.
- `ViewEjectorTest`, updated rather than deleted — decision 6.

## What this breaks

Nothing an application declares. Every change is to a URL the framework emits, and at an empty
prefix each one is byte-identical to what shipped — which is the property `base-path.md` decision 2
already relies on. The one visible difference at an empty prefix is F32's: a document key with a
space becomes `%20` where it was `+`, and the old form only ever addressed a document that did not
exist.

`ViewEjectorTest` changes its expected output, deliberately, and pages ejected before this campaign
keep the URLs they were given. They are the author's from the moment they are written.

## Filed, not fixed

- **`BulkReportRoundTrip` doubles the prefix on its `Location` too.** It is the third consumer that
  reads `_return` off the request and hands it to `BasePath.url` (`:126`), so it takes the same
  one-line correction as the other two. It is filed rather than ridden along because reaching it
  needs a bulk-report route in the prefixed fixture — a `report:` declaration and an `actions:`
  block — and a behaviour change here ships with a test that is red today or it does not ship. The
  two consumers this campaign could reach that way are fixed; this one is a slice with a fixture.

- **The plan's two extra `Templates.render` sites are not defects.** It names
  `FileResponseRenderer` and `TextResponseRenderer` as base-publishing sites the sweep misses.
  Both render in Thymeleaf **TEXT** mode — a generated config file or export, and an MCP
  `prompts/get` message — so neither composes a shell or emits a page URL. Publishing `base` there
  would be inert. Measured, not swept.

- **The ejector's other URLs are still root-absolute literals.** Fixing F28's three lookup legs
  put the rest in plain view: `ViewEjector` writes the form's `action` and `hx-post` as literal
  strings, and a list row's link as a bare literal substitution. Under a prefix an ejected page's
  lookup now resolves and its submit still posts at the origin. It is the same rule and the same
  file — three one-line changes — but it is a different subject from the lookup field, and the row
  link needs a decision about `{id}` inside a link expression that the lookup legs do not (their
  path is fixed at eject time). It is a slice, not a rider. The ejector already writes `@{}` for
  its two asset URLs, so the idiom is established in the file.

## Traps this campaign hit

Recorded as they were hit, so the next slice does not re-learn them.

1. **A hosted member fences every route behind `tql.app.use.<member>`.** `AuthStep.fence` is a
   no-op when the stack-member bean is absent, which is every unhosted boot — so a fixture copied
   from a plain-boot test compiles, boots, serves, and answers 403 on every page.
2. **A crawl that fetches WebJar assets must not negotiate HTTP/2.** The JDK client loses frame
   sync on a multi-megabyte static body and reports a frame type that does not exist; the guard
   pins HTTP/1.1 rather than re-running until it passes. The same signature has been seen before
   in this repository against a WebJar asset, so it is the client and the payload size, not the
   surface under test.
3. **A second javadoc orphans the first, and `-Werror` stops the build.** Adding a paragraph to
   an already-documented method by writing a new comment block above it produces "documentation
   comment is not attached to any declaration" — the Java-25 doclint failure this repository has
   hit before. Merge into the existing block.
4. **A stale surefire report reads as a result.** When a compile failure stops the run, the
   previous run's `.txt` is still on disk and still says what it said, so a failing build looks
   like a failing test. Check the build's own output, or delete the report first.
5. ~~**`clean` does not remove `tesseraql-runtime/file-uploads`.**~~ **Diagnosed and closed
   2026-09-06.** The mechanism was worse than "a stale directory": `HttpBadRequestTest` installed
   the default `BodyHandler`, which creates `cwd/file-uploads` on the **urlencoded** branch, not
   only for uploads — and this module runs surefire with `forkCount=1C` and `reuseForks=true`, so
   concurrent forks share `tesseraql-runtime/` as their working directory while
   `FileTransferIntegrationTest` asserts that directory does not exist. One test's side effect,
   another test's failure, on a machine with more than one core. The test posts nothing multipart,
   so it now takes `BodyHandler.create(false)` and asserts in an `@AfterAll` that it left nothing
   behind.

## Recorded deviations

**Closed in slice 5, as planned.** The reviewed-import surface joined the fixture with the slice
that fixed it. The original note follows.

**The reviewed-import surface joins the fixture in slice 5, not slice 3.** The slice list above
says the harness declares all four surfaces. Three of them are pure GET renders and crawl
directly; the import review page is not reachable without a multipart upload, a single-shot
review token, and a poll to a terminal job card. Building that machinery in the harness slice
would have put its most failure-prone fixture furthest from the assertions that justify it, so
the import surface is added by the slice that fixes it, where its upload is already needed. The
two import defects (the doubled `confirmAction`, and the job page publishing no `base`) were
both re-confirmed at HEAD while scoping this, and are recorded in
[What is broken](#what-is-broken) rather than deferred with the fixture.

## Open questions

1. **Does `pagePath` become base-relative?** Deferred by decision 3, with the ten wire-URL sites it
   would let us check listed there.
2. **The origin-fence activation segment.** An `_system` href carries the application prefix with
   no activation segment while a base-relative URL carries both. Neither the audit nor the plan
   filed it; it is a real inconsistency and it is not this campaign's.
3. **Dogfooding the fixture.** Adding a `lookup:` to `examples/user-admin-app` would make
   `StackModeIntegrationTest`'s existing origin-rooted assertion a second and much cheaper guard.
   Decision 7 chose a separate fixture; if the crawling boot proves expensive to maintain, this is
   the fallback.
4. **The gated dialect suites.** Slice 2 seeds document keys containing a space into the workflow
   and task tables. Whether the gated suites exercise those key columns is unverified, and the
   standing rule is to dispatch after a vendor-DDL-adjacent change rather than assume.

## Ride-alongs

Two corrections with the same root cause as the finding at the top of this document, folded into
the slices rather than scheduled:

- `base-path.md` gains a supersession banner. The repository already has the convention for this
  exact decision, at `app-isolation-model.md`.
- `reference-config.md` documents `tesseraql.http.basePath` as an operator key with an empty
  "Documented in" column. It is now a host-injected internal plus a lint switch, and it is
  described as one.

## What the plan got wrong

Recorded so nobody re-derives them from `REMEDIATION-PLAN.md`, which is stale on all of these.

1. **The five-line `ViewBinding.basePath` field is not equivalent** to threading an address —
   decision 5. This is the one that would have shipped green and broken.
2. **`ViewEjector` is unclaimed.** The plan sizes F28 as "six attributes, no Java". The ejector
   hand-writes the same three legs in Java, and an honest sweep turns an existing guard test red —
   which the plan does not anticipate.
3. **`RouteReloader`'s cited lines are the wrong ones**, and the item the plan's reviewer proposed
   closing with a confirming sentence has a real and negative answer — decision 9.
4. **The `ErrorResponseRenderer` collision is stale.** The plan sequences this campaign against two
   other campaigns on that file at a line number the http-edge campaign has since moved.
5. **The `## Unreleased` heading does not need re-adding.** Five campaigns each specify that their
   first slice restores it; it has been there since #1148.
6. **Design docs are registered one per pull request, not all at once.** The plan advises one
   up-front pull request for every campaign's document. The site's completeness check fails on an
   excluded entry whose file does not exist, so that pull request would red the docs job.
