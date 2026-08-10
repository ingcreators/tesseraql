# Serving an application under a base path

Status: **complete 2026-08-10** — every slice shipped; decisions 4, 5, 7 and 8 were settled
during implementation, which is where the last three came from.

Every URL a TesseraQL application emitted was rooted at `/`, so an application could only be
served at the root of its origin. That blocked two things: hosting
several applications on one origin under `/apps/<id>/`
([app-isolation-model.md](app-isolation-model.md) suite mode), and the ordinary case of a
single application behind a reverse proxy at `/myapp`.

## What is actually broken

Measured 2026-08-10 by starting a suite-mode gateway and requesting an HTML page through the
prefix. The page returns 200 and is unusable:

```html
<link rel="stylesheet" href="/assets/vendor/hypermedia-components__core/dist/hc.min.css">
<link rel="stylesheet" href="/assets/_tesseraql/tesseraql.css">
<form method="post" action="/_tesseraql/login">
```

Those URLs resolve against the origin, not the prefix, so the browser fetches them from the
gateway root — where nothing answers (verified: 404). No stylesheet, no script, and a login
form that posts into a void.

It went unnoticed because the multi-app tests exercise `/api/items`. A JSON API emits no
links, so it works under a prefix by accident.

The surface, counted:

| Where | Root-absolute URLs |
| --- | --- |
| Framework templates (`tql/**`) | 103 |
| Studio | 195 |
| account, auth-ui, ops console, IAM Admin | 69 |
| Java: redirects, cookie `Path`, live events, asset routes | 79 |

Plus every hand-written link in an application's own templates, which is why this is a
contract question and not only a sweep.

## Decisions

### 1. The base path is a runtime setting, supplied by config or by the host that starts it

`tesseraql.http.basePath` (default empty) is what the runtime serves under and what its
emitted URLs carry. A standalone application behind nginx at `/myapp` sets it in
configuration; suite-mode hosting passes `/apps/<id>` to each runtime it starts, derived from
the catalog id.

The app's files do not name their mount point. The same `.tqlapp` mounts at two prefixes in
two deployments, and the value is the operator's, not the author's.

That this serves the standalone reverse-proxy case is the reason to build it at all. Suite
mode is one consumer; "put the app under a path" is a request the framework cannot answer
today at any scale.

### 2. Templates use Thymeleaf link expressions, and one link builder resolves them

Templates write `th:href="@{/assets/x}"`. A `BasePathLinkBuilder` installed on the shared
`TemplateEngine` overrides `StandardLinkBuilder.computeContextPath` and returns the
application's prefix, so **the prefix rule lives in one method** rather than in every URL.
The renderer publishes `base` into the model for the builder to read; template authors never
name it.

**The load-bearing property is that an unset base renders byte-identical output** — the
builder returns the empty string, and `@{/assets/x}` is `/assets/x`, what ships today.

**Corrected 2026-08-10.** This document first decided the opposite: templates would carry
`th:href="|${base}/assets/…|"`, and `@{…}` was rejected on the grounds that "it derives a
context path from a servlet environment TesseraQL does not render in". **That reason was
wrong.** Thymeleaf 3.1 exposes `ILinkBuilder`, and `StandardLinkBuilder.computeContextPath`
is precisely the hook for supplying a context path without a servlet container.

The string-concatenation approach was implemented first and produced three classes of bug in
one sitting, all of them invisible until a page was rendered: a duplicated `th:src` where an
element already carried one, silently skipped URLs that were already expressions, and a
second sweep that fixed the first and missed the same cases from the other direction. Four
hundred string concatenations are four hundred chances to make each of those. One overridden
method is one.

### 3. A hand-written root-absolute link is a lint warning, not a break

When `basePath` is set, lint warns on `href`/`src`/`action` values starting with `/` in the
application's own templates. A warning rather than an error: an application may legitimately
link off-site or to a path outside its own prefix, and the framework cannot tell which.

Applications that never set a base path are never warned, so the lint is silent for everyone
until the day it is useful.

### 4. The host supplies the cookie path, because only the host knows it is a suite

The session cookie is issued with `Path=/` in three places. Under a base path there are two
correct answers and they conflict:

- **`Path=<basePath>`** is right for a standalone application behind a proxy: its cookie
  should not be sent to whatever else lives on that origin.
- **`Path=/`** is right for suite mode, whose *purpose* is one session across the suite
  ([app-isolation-model.md](app-isolation-model.md) decision 2). Scoping per prefix would
  make each app a separate sign-in and delete the mode's reason to exist.

The cookie path is therefore not derivable from the base path, and it is **supplied
alongside it by whatever starts the runtime**. A standalone `serve` uses the base path; a
suite-mode gateway passes `/`, because it is the component that knows these applications are
one suite. An isolated-mode gateway passes each app's own path, since there is nothing to
share.

A separate configuration key was considered and rejected: an operator who sets it wrongly
gets either a silently unshared suite or a session offered to every neighbour on the origin,
and neither failure announces itself. The knowledge belongs to the host, so the host carries
it.

### 6. One REST configuration carries the prefix, not one concatenation per route

`restConfiguration().contextPath(basePath)` is set on Camel's **context-wide** REST
configuration, and every REST route the runtime mounts inherits it — the application's own,
and the framework's hand-written `/_tesseraql/**` endpoints alike. Verified: with a prefix
configured, `/apps/shop-a/_tesseraql/health` answers 200 and `/_tesseraql/health` answers
404, without any of those endpoints being touched.

`RouteReloader` restates it, because a reloaded or stubbed route re-enters the same
configuration and a hot reload must not quietly move a route out from under the prefix.

**Corrected 2026-08-10, and this is the second time the same mistake was made.** Slice 1
concatenated the prefix inside `RouteCompiler.restEndpoint`, and this document planned slice
2 as threading the prefix through six route builders to reach **47 hand-written
`rest().get("/_tesseraql/…")` calls**. None of that is needed, and none of it is done.

The pattern is worth naming, because it also produced the `${base}` mistake corrected in
decision 2: **a slice sized in hundreds of call sites is evidence of a missing extension
point, not a large job.** 446 template URLs meant a link builder was being overlooked; 47
route mounts meant a REST configuration was. Both libraries offered exactly one place to put
the rule. Check for that place before counting the call sites.

### 7. A URL is base-relative inside the runtime and acquires the prefix on its way out

Slice 2 had to answer a question decision 2 left open for everything that is not markup: *when*
does a URL become prefixed? Two answers were both live in the code and they contradict each
other — a `Location` built from a route's declared path (`/api/items/import`) needs the prefix,
and a `Location` built from `HTTP_URI` already carries it, because the runtime serves under the
prefix and the request arrived at the full address.

The rule, now stated once in `BasePaths`: **a URL is base-relative everywhere inside the runtime
and acquires the prefix at the moment it becomes a wire URL.** In markup that moment is the link
builder; in a response header it is `RedirectRenderer.negotiate`, the framework's one redirect.
A URL read back off the request is already a wire URL and is left alone — which is why the login
page's `next` target is stored base-relative: it is handed back to the redirect helper after
sign-in, and would otherwise be prefixed twice.

The single sign-on post-login targets follow the same rule: an absolute URL is the provider's
and is left alone, a local target is base-relative and acquires the prefix on the way out. A
link that hands SSO a wire URL as its `next` or `RelayState` would be prefixed twice, which is
why the framework's own surfaces hand it a base-relative one.

The one deliberate exception is pins and recents, which the browser captures from its own
location bar. Those are wire URLs by origin, they are compared against the request URI, and they
are per-user state in a database, so re-deriving them costs more than it settles.

### 8. The framework's own JavaScript resolves against the module, not the origin

Slice 3 found the surface no link builder reaches: the bundled `.js` assets, which import
`/assets/vendor/…` and `fetch("/_tesseraql/…")` as absolute URLs. Under a prefix all of them
resolve at the origin and 404 — the same defect as the markup, in a place markup rules cannot
see.

They do not need a base path threaded into them, because **each module is itself served from
under the prefix**. A module specifier resolves against the importing module's URL, so
`../vendor/hc.behaviors.min.js` is right at every mount point; a `fetch` resolves against the
document instead, so those take `new URL("../../_tesseraql/…", import.meta.url)`. Six sites,
no configuration, and nothing to keep in sync.

The generated per-locale message module (`ClientMessages`) is the same story and lost the
`basePath` parameter it was first given.

This narrows the "absolute URLs in application JavaScript" exclusion below rather than removing
it: an application's own scripts are still the author's, and `import.meta.url` is the idiom to
point them at.

### 5. The runtime serves under the prefix; it does not merely emit it

Two models exist for putting an application under a path, and both are real deployments:

- **Prefix-aware serving** — the runtime binds `<base>/users`, and the proxy passes the path
  through unchanged.
- **Prefix-stripping** — the proxy removes `/myapp`, the runtime serves `/users` as today,
  and only its *emitted* URLs carry the prefix.

TesseraQL takes the first. An application that emits `<base>/users` should answer at
`<base>/users`: the alternative leaves a runtime advertising URLs it cannot serve, which is a
standing invitation to inconsistency and cannot be tested without a proxy in front of it.
Prefix-aware serving also works behind a proxy that does nothing but pass paths along, which
is the configuration an operator is most likely to get right.

The cost is real and accepted: the multi-app gateway currently strips `/apps/<id>` before
forwarding and stops doing so, and route mounting learns the prefix. Prefix-stripping would
have been the smaller change.

## Slices

1. **The setting and the link builder.** `tesseraql.http.basePath`, prefix-aware route
   mounting (decision 5), `BasePathLinkBuilder` on the shared engine, and the sweep of
   framework templates (`tql/**`) to `@{…}`. An unset base is byte-identical, held by a test.
   The gateway stops stripping the prefix and starts supplying it to each runtime in the same
   slice, since the two halves must move together. **Done** (#695, corrected by #696).
2. **The framework's own URL emission.** What remains after decision 6: the asset route
   (`platform-http:/assets`, outside the REST DSL and so outside `contextPath`), redirects,
   the live-events endpoint, and generated OpenAPI servers. The 47 REST mounts this slice was
   sized around need no change. **Done** — four mount points and one redirect helper
   (decision 7), plus the framework templates' remaining non-link attributes: the command
   palette's `data-value`, the shell's `sse-connect`, and the model-supplied URLs the view
   patterns render (`v.action`, `cell.href`, `liveConnect`), which are base-relative and so go
   through `@{${…}}`.
3. **The bundled apps.** Studio, ops console, IAM Admin, account, auth-ui — the 264 sites,
   mechanical once slice 1 defines the idiom. **Done** — 260 attributes rewritten to
   `th:href="@{/x}"` (Thymeleaf's default attribute processor gives `th:hx-post` and
   `th:data-value` for free, so htmx attributes need no `th:attr` list), 47 literal
   substitutions `th:href="|/x/${id}|"` that read as though they were already dynamic, 39
   model-supplied URLs through `@{${…}}`, the Studio preview `srcdoc` head, and the JavaScript
   of decision 8. A test walks the shipped templates and fails on a root-absolute URL, because
   a sweep this size is worth doing once.
4. **The lint** (decision 3). **Done** — `TQL-TPL-2004`, a warning over the application's own
   `web/**` and `templates/**` markup, raised only when a base path is configured. It catches
   the literal-substitution spelling too, that being how forty-seven of the framework's own
   URLs survived slice 3's first pass.
5. **The cookie path**: supplied by the host beside the prefix (decision 4). **Done** —
   `TesseraqlRuntime.start` takes it beside the base path, the suite gateway passes `/`, and a
   standalone start defaults to the application's own prefix. The seven places that assembled
   the header by hand, in five modules, agreeing by copy, became one `SessionCookie`. The
   OIDC flow cookie is the exception that proves the rule: scoped to `/_tesseraql/oidc`, it
   follows the *base* path, because it is scoped to endpoints rather than to a sign-in.
6. **Suite mode end to end**: an HTML page served through `/apps/<id>/` with its assets,
   navigation, forms, and htmx swaps working — the case that opened this document. **Done** —
   `SuiteModeIntegrationTest` asks for the page, then asks for every URL the page named, which
   is the check the original defect would have failed. It also covers Studio behind the gateway
   ([app-isolation-model.md](app-isolation-model.md) slice 5), the sign-in redirect, one
   sign-in reaching both applications, and the converse under independent hosting. The example
   application's own templates moved to link expressions with it, the scaffolder's generated
   fragments included — the first application to meet the lint of slice 4 was ours.

## Out of scope

- Rewriting HTML at the gateway. The application knows its own base path; a proxy guessing
  at someone else's markup does not, and would have to parse JavaScript and data attributes
  to be correct.
- Absolute URLs in application JavaScript. Slice 4's lint sees markup; a script that builds
  a URL from a string literal is the author's to fix.
- Per-request base paths. The prefix is fixed for a runtime's lifetime.

## Interim honesty

**Closed by slice 6.** `tesseraql host --mode suite` shipped serving JSON APIs and not HTML
applications, and said so in its help text rather than letting an operator discover it from a
page with no stylesheet. It now serves both, and the help text no longer carries the caveat.
