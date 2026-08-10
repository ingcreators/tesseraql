# Serving an application under a base path

Status: design 2026-08-10, accepted in principle; the open question in decision 4 is called
out rather than settled. Every URL a TesseraQL application emits is rooted at `/`, so an
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

### 2. Templates read `${base}`, and an unset base changes nothing

The renderer already assembles `_csrf`, `_menu`, `_account` into every model
(`HtmlResponseRenderer`). It gains `base`: the configured prefix, or the empty string.
Framework templates become `th:href="|${base}/assets/…|"`, and the framework generates the
same prefix into redirects, asset URLs, and the live-events endpoint.

**The load-bearing property is that an unset base renders byte-identical output.** `${base}`
is `""`, so `|${base}/assets/x|` is `/assets/x` — what ships today. Every existing
deployment, which is every deployment, is unaffected. The contract only changes for someone
who opts into a base path.

Thymeleaf's `@{…}` link syntax was considered and rejected: it derives a context path from a
servlet environment TesseraQL does not render in, so it would need the same variable wired
underneath while also rewriting all 446 sites into a second syntax.

### 3. A hand-written root-absolute link is a lint warning, not a break

When `basePath` is set, lint warns on `href`/`src`/`action` values starting with `/` in the
application's own templates. A warning rather than an error: an application may legitimately
link off-site or to a path outside its own prefix, and the framework cannot tell which.

Applications that never set a base path are never warned, so the lint is silent for everyone
until the day it is useful.

### 4. Open: the session cookie's `Path` (suite sharing versus proxy correctness)

The session cookie is issued with `Path=/` in three places. Under a base path there are two
correct answers and they conflict:

- **`Path=<basePath>`** is right for a standalone application behind a proxy: its cookie
  should not be sent to whatever else lives on that origin.
- **`Path=/`** is right for suite mode, whose *purpose* is one session across the suite
  ([app-isolation-model.md](app-isolation-model.md) decision 2). Scoping per prefix would
  make each app a separate sign-in and delete the mode's reason to exist.

So the cookie path is not derivable from the base path alone; it needs its own setting, or
the host must supply it alongside the prefix. **This is not settled here.** The slices below
stop short of it, and it is decided before the cookie work lands — with a bias toward the
host supplying both, since it is the host that knows whether the apps are a suite.

## Slices

1. **The setting and the model variable.** `tesseraql.http.basePath`, route mounting under
   it, `base` in the template model, and the sweep of framework templates (`tql/**`). An
   unset base is byte-identical, held by a test.
2. **The framework's own URL emission.** Redirects, asset routes, the live-events endpoint,
   generated OpenAPI servers.
3. **The bundled apps.** Studio, ops console, IAM Admin, account, auth-ui — the 264 sites,
   mechanical once slice 1 defines the idiom.
4. **The lint** (decision 3).
5. **The cookie path**, after decision 4 is settled.
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
