# Serving an application under a base path

Status: design accepted 2026-08-10; decisions 4 and 5 settled the same day, after
implementation surfaced the second. Every URL a TesseraQL application emits is rooted at `/`, so an
application can only be served at the root of its origin. That blocks two things: hosting
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
   slice, since the two halves must move together.
2. **The framework's own URL emission.** Redirects, asset routes, the live-events endpoint,
   generated OpenAPI servers.
3. **The bundled apps.** Studio, ops console, IAM Admin, account, auth-ui — the 264 sites,
   mechanical once slice 1 defines the idiom.
4. **The lint** (decision 3).
5. **The cookie path**: supplied by the host beside the prefix (decision 4).
6. **Suite mode end to end**: an HTML page served through `/apps/<id>/` with its assets,
   navigation, forms, and htmx swaps working — the case that opened this document.

## Out of scope

- Rewriting HTML at the gateway. The application knows its own base path; a proxy guessing
  at someone else's markup does not, and would have to parse JavaScript and data attributes
  to be correct.
- Absolute URLs in application JavaScript. Slice 4's lint sees markup; a script that builds
  a URL from a string literal is the author's to fix.
- Per-request base paths. The prefix is fixed for a runtime's lifetime.

## Interim honesty

Until slice 6 lands, **suite mode serves JSON APIs and not HTML applications**. That is the
state `tesseraql host --mode suite` shipped in, and the flag's help text and the hosting
documentation say so rather than letting an operator discover it from a page with no
stylesheet.
