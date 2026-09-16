# Unicode names at the router: an application named in Japanese is addressed, redirected to, returned to and shadowed by nothing

> **Status: complete.** One pull request. **R0** — the gateway compares a member's prefix in the
> wire's spelling, so a member named `受注` is addressed at `/%E5%8F%97%E6%B3%A8` and
> `root.redirect: 受注` writes a `Location` the browser can follow. **R1** — a wire URL read back
> off the request is returned to base-relative form under a non-ASCII base (`_return` no longer
> doubles its prefix), and the session cookie's `Path=` is published as wire text. **R2** — a
> literal segment is mounted before a `{parameter}` sibling at the first position where two
> routes differ, whatever the characters, so `/受注/エクスポート` is no longer answered by
> `/受注/{受注番号}`. **R3** — `unicode-identifiers.md` no longer says an application name stays
> ASCII.
>
> **The decision this slice rests on** (the user, 2026-09-14): a non-ASCII `tesseraql.app.name` is
> legal, as `ApplicationName.java` has said since the unicode-identifiers campaign, and the
> gateway is what changes — not the name grammar. `docs/unicode-identifiers.md:160` said the
> opposite ("app names stay ASCII, `[a-z][a-z0-9-]{0,63}`"), the code never enforced it, and
> `base-path-emission.md` already promised `/受注管理` as an address. Three documents, two
> positions; the code's position is the one kept.

Registered in `docs-site/nav.mjs` `EXCLUDED` and `ErrorIndex.INTERNAL_DOCS` with this record,
the registration asserted in `InternalDocsSyncTest`. Filed by
[`download-name-and-bytes.md`](download-name-and-bytes.md) ("Filed, not fixed", the router
bullet) and carried as items 16, 18 and 19 of [`audit-medium-leads.md`](audit-medium-leads.md).

---

## The measured premise

Every item has the same mechanism: **the wire carries percent-encoded UTF-8
(`/%E5%8F%97%E6%B3%A8`), the framework holds the name as characters (`/受注`), and a comparison
or a header write met the two without translating.** Measured on `b45ded821` (2026-09-14) by
reading and by the guards below run before the fix.

| # | item | verdict | where |
|---|---|---|---|
| R0 | a non-ASCII `tesseraql.app.name` is hosted but unaddressable at the gateway | LIVE — `StackRelay.appAddressedBy` compared `entry.basePath()` (`/受注`) to `request.uri()` (`/%E5%8F%97%E6%B3%A8/…`): no member matched, 404 TQL-APP-4040 on every request. RUN: a `受注` member beside `shop-a`/`shop-b`, `GET /%E5%8F%97%E6%B3%A8/api/items` → 404. The stream-forward fence (`isStreamForward`) and the activation address (`ActivationAddress`) compared the same way. | `StackRelay:417-434, :357-372, :648-670` |
| R0 | `root.redirect` to a non-ASCII member loops | LIVE — the 307's `Location` was the raw name; the transport folds a character above U+00FF to `?`. RUN: `Location: /???q=1`. Not a loop: a 404 after the redirect. | `StackRelay:509` |
| R1 | `BasePaths.relative` on a wire-spelled `_return` under a non-ASCII base doubles the prefix | LIVE — `relative("/受注", "/%E5%8F%97%E6%B3%A8/things?page=2")` returned its input, and `BasePath.url` joined a second prefix. RUN, unit. | `BasePaths.relative` |
| R1 | `Set-Cookie Path=` on a non-ASCII base path | LIVE, file-only as filed — `CookiePath.bind` published the raw path; a browser compares `Path=` to the percent-encoded request path, so no request matched the cookie. Unreachable from `dev`/`host`, whose cookie path is `/` (the shared sign-in). RUN, unit. | `CookiePath.bind` |
| R2 | route shadowing by sort order | LIVE — every route mounts at `AFTER_THE_GATE` and ties fall to the manifest's file order, where `{` (U+007B) sorts after every ASCII letter and before every CJK character: `/受注/{受注番号}` before `/受注/エクスポート`, which answered with `受注番号=エクスポート` — the detail's empty result, 200, the wrong route. RUN: the juchu-kanri gallery with a literal sibling added in the test's copy. | `RouteEdge.mountRoute` |
| R3 | `unicode-identifiers.md:160` vs `ApplicationName.java:59` | the document said ASCII, the code said not confined, the base-path record promised `/受注管理` | docs |

---

## The decisions

1. **The name grammar does not change; the gateway does.** `ApplicationName` keeps its
   segment-safety rule (one segment, no leading `_` or `.`, no dot, two reserved words); a
   non-ASCII name is legal and is the application's address — `/受注` — spelled
   `/%E5%8F%97%E6%B3%A8` on the wire. The alternative, refusing non-ASCII names at lint and boot,
   would have made the framework's own unicode-identifiers promise stop at the front door.
2. **The gateway compares in the wire's spelling** (R0): one helper, `wirePrefix(member)` =
   `PercentEncoding.uriLiteral(member.basePath())`, at the three sites that meet a request
   line — the member match, the stream-forward fence, the activation address. The request line's
   own percent-escapes are folded to upper-case hex for the comparison only (a browser sends
   upper case, a hand-written client may not, RFC 3986 makes them the same octet); the URI
   forwarded to a member stays exactly as the client sent it. ASCII is its own wire form, so
   every ASCII member compares exactly as before.
3. **The root redirect's `Location` is wire text** (R0): `uriLiteral(rootTarget)`, the same
   encoder every framework redirect uses; the query string rides along as before. *Amended
   in `docs/audit-low-leads.md` slice 9 (unfiled 47): the query rides through the same encoder
   — Netty accepts a DEL or C0 byte in a request-target that Vert.x refuses in a header, so a
   query echoed raw was a 502 blamed on the member; an authored `%XX` triplet is kept, so a
   well-formed query is byte-identical.*
4. **`BasePaths.relative` strips the wire spelling of the base first, then the raw one** (R1):
   a URL read back off the request is wire text; a caller passing decoded text still strips.
5. **`CookiePath.bind` publishes wire text** (R1): the `Path=` attribute is what the browser
   compares to the request path it sent. ASCII unchanged.
6. **A route's order is its specificity** (R2): `AFTER_THE_GATE + specificity(path)`, a bit per
   segment (the first most significant) set where the segment is a parameter, so two routes
   compare segment by segment with literal before parameter at the first difference — the
   manifest's file order (which the lint and the coverage listing still use) no longer decides
   which route answers, and a route the file watcher adds lands in its place rather than
   behind everything mounted before it. Thirty segments fit below the hand-written surfaces'
   order; a deeper path's tail is not ranked. The hand-written surfaces (assets, health) keep
   `AFTER_THE_GATE` and the literal-only application routes tie with them by insertion, as today.
7. **The document says what the code does** (R3): `unicode-identifiers.md`'s "what deliberately
   stays ASCII" loses application names and gains the rule.

## The guards, red before the fix

- `MultiAppGatewayIntegrationTest` (a `受注` member beside `shop-a` and `shop-b`):
  `aMemberWithANonAsciiNameIsAddressedAtTheGateway` — `/%E5%8F%97%E6%B3%A8/api/items` and its
  lower-case-hex spelling answer 200, a stranger's path and an over-long prefix 404;
  `theRootRedirectsToANonAsciiMemberOnTheWire` — `root.redirect: 受注` answers
  `Location: /%E5%8F%97%E6%B3%A8?q=1` and the member answers under it.
- `BasePathsTest.relativeStripsTheWireSpellingOfANonAsciiBase`; `CookiePathTest` (R1).
- `JapaneseIdentifiersIntegrationTest.aJapaneseLiteralSegmentIsNotShadowedByItsParameterSibling`
  (R2): the gallery's `/受注/{受注番号}` with a literal `/受注/エクスポート` beside it, added in the
  test's copy — the literal answers its own marker row.

Bracket (`work/router-unicode-names/`): HEAD `b45ded821` — the gateway rows red (404; `Location:
/???q=1`), the two R1 rows red (the input unchanged; `/受注` raw), the R2 row red (the detail
route's empty list); `V-no-hex-fold` (the fold as identity) — exactly the lower-case-hex row;
`V-raw-location` — exactly the redirect row; `V-raw-prefix` (the member match back to the raw
base path) — both Japanese gateway rows; `V-single-order` (R2's order back to `AFTER_THE_GATE`)
— exactly the shadowing row; the fix — 14/14, 3/3, 2/2, 5/5.

## What this breaks

- A stack that relied on a non-ASCII member being unreachable — nothing can have.
- A route whose answer depended on the file order between a literal and a parameter sibling
  now gets the literal; every ASCII case already did.

## Filed, not fixed

- The stream-forward fence and the activation address for a non-ASCII member are rewired by
  the same helper and exercised by the ASCII members' existing tests (the helper is the identity
  on ASCII); a Japanese member's SSE stream and `/_as/<role>/` activation are not run here —
  green by construction, disclosed.
- `StackRelay`'s other header writes (`Retry-After`, `Content-Type`) are ASCII by construction.
- An application name the file system spells in NFD (macOS) versus the catalogue's NFC — the
  unicode-identifiers campaign's `ScaffoldChecksum` rule; not measured at the gateway.
