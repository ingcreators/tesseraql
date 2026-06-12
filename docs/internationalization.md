# Internationalization

An app serves every locale from one set of templates, SQL, and YAML (roadmap Phase 22):
message catalogs translate the UI and the error model, the request locale negotiates per
user, and date/number inputs parse the way that user writes them.

## Message catalogs

One YAML file per BCP-47 language tag under the app home's `messages/` directory; nested
maps flatten to dotted keys:

```
messages/
  en.yml
  ja.yml
```

```yaml
# messages/ja.yml
users:
  list:
    title: ユーザー一覧
  provision:
    unknown-user: 指定されたユーザーは存在しません。
```

Lookup walks the exact tag, then the bare language (`ja-JP` → `ja`), then the app default
locale. App catalogs layer over the framework's built-in texts (`tql.*`, shipped in English
and Japanese), so any framework message can be overridden per app. Texts may carry `{name}`
placeholders — the same syntax the Hypermedia Components client catalog interpolates — filled
from the error entry they describe (constraint params, violation row columns).

## Configuration and locale resolution

```yaml
tesseraql:
  i18n:
    defaultLocale: en            # the app's authoring locale (default en)
    locales: [en, ja]            # served locales; defaults to the catalogs found
    preference:                  # user-preference sources, highest priority first
      - query.lang               # ?lang=ja — a language toggle without sign-in
      - principal.claim.locale   # the signed-in user's locale claim (the default source)
```

Every route resolves its locale once, right after authentication: the preference sources in
order, then `Accept-Language` negotiation (RFC 4647 lookup, so `ja-JP` matches a supported
`ja`), then the default. The result publishes as the `request.locale` source expression —
usable anywhere format sources are (`export: { locale: request.locale }`) — and drives
everything below. An unsupported preference falls through to the next source instead of
serving an untranslated locale.

## Templates

`#{key}` message expressions resolve from the catalogs with the request locale, and
`#locale` follows it (the framework shell sets `lang` from it):

```html
<h2 th:text="#{users.list.title}">Users</h2>
<input class="hc-input" type="search" th:placeholder="#{users.list.searchPlaceholder}">
```

A missing key renders as the standard `??key_locale??` marker so gaps stay visible (lint
reports them at build time, below). Locale-less renders — mail bodies, generated file
responses — read the English/default texts.

## Localized errors

Error responses localize at render time with the request locale. A field error's declared
key keeps riding as `messageKey` while `message` carries the resolved text; the top-level
`message` is the localized status phrase (`tql.http.<status>`):

```json
{"error": {"code": "TQL-FIELD-4220", "message": "入力内容を確認してください",
  "fields": [
    {"rule": "userExists", "field": "userName", "code": "unknown-user",
     "messageKey": "users.provision.unknown-user",
     "message": "指定されたユーザーは存在しません。"}]}}
```

- Validation rules declare keys as before (`message: users.provision.unknown-user`).
- Input-constraint rejections (required/min/max/maxLength/enum/type) are field-scoped errors
  with built-in keys (`tql.input.required`, `tql.input.min`, ...), translated by the
  framework catalog and overridable per app.
- Mapped constraint violations fall back to `tql.constraint.<code>` texts (`duplicate`,
  `required`, ...); an `errors.constraints` mapping may declare its own `message:` key.
- The optimistic-locking conflict hint is the `tql.conflict.stale` key, resolved per locale
  (`hintKey` keeps the key, `hint` the text).

htmx fragments carry the localized text as the item body and the key as `data-message-key`,
so the kit's client catalog can re-resolve it after a swap.

## Locale-aware input parsing

`date`, `datetime`, and `number` inputs parse with the request locale and an optional
`format` pattern — the same machinery as file-transfer columns
(`DateTimeFormatter`/`DecimalFormat`):

```yaml
input:
  orderDate:
    type: date
    format: yyyy/MM/dd        # 2026/06/12 → a LocalDate bind parameter
  amount:
    type: number
    format: "#,##0.##"        # 1.234,56 under de-DE → BigDecimal 1234.56
```

Without `format`, `number` keeps ISO parsing and `date`/`datetime` accept the ISO defaults.
A bad value answers `400` with the matching `tql.input.<type>` field error.

## The client catalog (Hypermedia Components)

The shell loads `/assets/_tesseraql/messages.js?locale=<tag>` before the framework
bootstrap: an ES module that imports `setMessages` from the kit's behaviors bundle and
merges the app's catalog entries over the framework's Japanese translations of the kit's
built-in strings (confirm dialog, combobox, calendar, shell). Module scripts execute in
document order, so the catalog lands before the behaviors install at `DOMContentLoaded`.
English needs no module content — the kit's own defaults apply.

## Testing and lint

Declarative suites assert on catalogs directly — one row per key with `key`, `locale`, and
`text` columns:

```yaml
- name: the japanese catalog translates the provisioning error
  messages:
    locale: ja
    keys: [users.provision.unknown-user]
  expect:
    rows:
      - text: 指定されたユーザーは存在しません。
```

A `message` coverage kind declares every shipped catalog by its language tag and counts it
covered when a messages case reads it, gated via `coverage.thresholds.kinds.message`.

Lint checks the catalogs statically when `messages/` exists:

| Code | Severity | Finding |
| --- | --- | --- |
| `TQL-YAML-1007` | error | a catalog file is malformed or its name is not a BCP-47 tag |
| `TQL-YAML-1103` | warning | a declared `tesseraql.i18n.locales` entry has no catalog |
| `TQL-YAML-1008` | warning | a catalog misses keys present in the default locale |
| `TQL-FIELD-2005` | warning | a declared message key has no default-locale text |
