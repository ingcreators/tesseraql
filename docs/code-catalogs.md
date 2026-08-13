# Code catalogs

Most business databases carry a handful of small tables that barely change: payment
methods, order statuses, transaction types, currencies. Rows elsewhere store the
**code**; screens and documents have to show the **name**.

A code catalog is how TesseraQL answers that. The catalog is loaded whole and held, so
resolving a name costs no query at all. A page showing twenty coded columns still makes
one query — the one that fetched the page's rows.

## Declaring a catalog

Catalogs live in `catalogs/*.yml` under the app home. Files merge into one app-wide
namespace, and a name declared twice is a build error.

```yaml
# catalogs/codes.yml
version: tesseraql/v1
catalogs:
  payment_method:
    table: code_master
    where: { code_type: 'PAY' }   # this catalog's slice of a shared master
    key:   code
    label: label
    order: sort_order             # the order a form offers the codes in
    active: active                # which codes may still be chosen
```

`where:` is what makes one physical table serve many catalogs. A general code master
usually holds every kind of code, keyed by a type column. Pin the type here and the
catalog itself is single-keyed, which is what lets a field reference it.

Two keys are worth understanding early.

**`active:`** marks the codes a form still offers. It does not affect resolution: names
resolve over *every* row, so a retired code still renders on last year's orders and is
simply not offered on today's form.

**`order:`** decides the order of a form's options. Sorting by label would be wrong —
collation is locale-dependent, and code masters already carry a display-order column.

## Showing the name

In a hand-written template, the catalogs are in the model under `codes`:

```html
<td th:text="${codes.payment_method.of(row.payment_method)}">Bank transfer</td>
```

`of(...)` returns the name, or the code itself when the catalog has no name for it. A
missing name is a gap in the master data, not a reason to blank a cell in a document
someone is reading.

In a [declarative view](declarative-views.md), point the column at a domain instead:

```yaml
columns:
  - name: order_no
  - name: payment_method
    domain: payment_method
```

```yaml
# domains/codes.yml
version: tesseraql/v1
domains:
  payment_method:
    type: string
    maxLength: 8
    codes: payment_method   # the catalog this field's values come from
```

One declaration, and the name renders everywhere that column does: a list, a detail
view's field, a detail view's child table, a dashboard's table panel, and the template
the page is ejected into. Ejecting keeps calling the catalog, so a code renamed next
month renames on the ejected page too.

## Validation and form options

The same `codes:` reference gives the field its rules on the write side.

The input binder accepts only the catalog's **active** codes. The violation is the
`enum` field error, because a catalog is a dynamic enum — nothing downstream learns a
second shape. A value the held copy does not carry is re-read from the source before it
is refused, so a code added a minute ago is never rejected for the length of the hold.

On a form, a `codes:` field renders as a `<select>` whose options are the active codes
in the catalog's order. An ejected form keeps reading them rather than freezing today's
codes into markup.

## Names in more than one language

Language is a **dimension** of the catalog, not part of its key. Add the column and the
call site does not change:

```yaml
  payment_method:
    table: code_master
    where: { code_type: 'PAY' }
    key: code
    label: label
    language: language
```

One code, one row per language it is written in:

| code | language | label |
| --- | --- | --- |
| `TRANSFER` | `en` | Bank transfer |
| `TRANSFER` | `ja` | 振込 |
| `CASH` | `en` | Cash |
| `CASH` | `ja` | 現金 |

The catalog answers in the surface's locale. On a route that is the request's resolved
locale, negotiated exactly as [internationalization](internationalization.md) describes.
A code with no name in that language falls back to the **default language**, not to the
raw code.

The key set never narrows with the labels. Whether a code may be written is a question
about the key set, so a missing translation can never turn into a failed transaction.

An **export** answers in its own `locale:`, not the requesting browser's. An export has
no request to negotiate a locale from — it is often generated on a schedule and read by
someone who never made a request. When your catalogs carry per-language names, an export
must declare `locale:` (or `tesseraql.files.locale`); the build refuses the undeclared
case rather than letting the server's locale decide.

## When a table and filters are not enough

Some masters keep the codes in one table and their per-language names in another.
`table:`/`where:` cannot express that join, so declare the SQL instead:

```yaml
  currency:
    file: currency.sql                    # resolved under catalogs/
    tables: [currency, currency_name]     # what the SQL reads
    key: currency_code
    label: name
    language: language
    active: active
```

`key:`/`label:`/`language:`/`active:` name that SELECT's **result columns**, exactly as
they name a table's. `tables:` is what lets a maintenance screen invalidate the catalog
without anything having to parse SQL. The SQL owns its own filtering and ordering, so
`where:` and `order:` are refused beside `file:`.

## Names from the message catalog

A code's name is often already a translated string. Take it from the message catalog
rather than adding a per-language table beside the codes:

```yaml
  priority:
    table: priority_master
    key: priority
    label: { message: "code.priority.{key}" }
    order: sort_order
```

```yaml
# messages/en.yml
code:
  priority:
    H: High
    L: Low
```

```yaml
# messages/ja.yml
code:
  priority:
    H: 高
    L: 低
```

The load then says only which codes exist. Each code becomes one entry per locale the
app supports, so the language dimension comes from the translation workflow the
[Studio](studio.md) message editor already serves.

## Keeping a catalog fresh

A catalog is held for `cache.maxAge` (an hour by default). Three things keep it current.

**A maintenance write says which table it touched.** The command drops every catalog
that reads that table, so the next screen shows what was just saved:

```yaml
# web/admin/codes/post.yml
steps:
  upsert: { file: upsert-code.sql }
invalidates: [code_master]
```

It names the **table**, not the catalog. A maintenance screen for a shared master writes
a row whose kind is request data, so which catalog is affected is not known until the
row is written. Over-invalidating costs nothing here: a catalog is small enough to hold
whole, so reloading all twenty is twenty small queries. `scaffold crud` writes the
declaration itself for a table a catalog reads.

**Other runtimes learn from a version row.** The write also raises a per-table version,
and every runtime re-reads that table on a short interval — one small query for all
catalogs at once.

**An operator can look and refresh.** `GET /_tesseraql/ops/catalogs` reports what each
catalog holds, when it last loaded, and the message of its last failed refresh. A
catalog serving a previous load while its refresh keeps failing shows both facts.
`POST /_tesseraql/ops/catalogs/{name}/refresh` re-reads one, gated by `ops.batch.run`.

None of this is the guarantee. `invalidates:` is an optimization — a master written by
another system raises nothing. Underneath sit the hold's expiry and the validation
path's re-read, so a stale catalog is a display delay and never a wrong rejection.

## Catalog or enrichment?

The choice is **size**, not key arity.

A master of tens or hundreds of rows that barely changes is a catalog: loaded once,
resolved from memory, no query per request. A master of thousands that moves is a
reference, fetched by key with [`enrich:`](response-shaping.md#fetching-a-reference-by-key).

Both resolve composite keys, and both read the same at the call site.

## Deliberately out of scope

- **Sorting, searching or paginating by a resolved name.** Resolution happens after the
  query, and only SQL can order a result set. A screen that must sort by name joins the
  master instead. A `sortable:` coded column orders by the code.
- **Reading a catalog from SQL.** Catalogs are a rendering and validation facility. A
  value your SQL must compute with — a tax rate — belongs in a table you join, keyed by
  `(code, valid_from)`, and the result belongs on the document you wrote it onto.
- **Per-tenant catalogs.** A catalog is held app-wide, so an app that declares catalogs
  alongside per-tenant datasources is refused rather than serving one tenant's codes to
  another.

## Lint and error surface

| Code | What it catches |
| --- | --- |
| `TQL-FIELD-4616` | a catalogs document is malformed or declares an unknown key |
| `TQL-FIELD-4617` | a catalog name is declared twice |
| `TQL-FIELD-4618` | a catalog names something that is not a legal identifier |
| `TQL-FIELD-4619` | per-language names in an app that negotiates one locale |
| `TQL-FIELD-4620` | `invalidates:` that drops nothing, or on a recipe with no commit |
| `TQL-FIELD-4621` | a contradictory source, or a `file:` that is not there |
| `TQL-FIELD-4622` | an export that renders codes but declares no locale |
| `TQL-APP-4206` | a catalog could not be loaded and has never loaded |
| `TQL-APP-4207` | catalogs declared alongside per-tenant datasources |

## In the editor

`catalogs/*.yml` has its own JSON Schema, scaffolded into `.vscode/` and associated with
`catalogs/**/*.yml`, so completion and validation work offline like every other authored
document. The [VS Code extension](vscode-extension.md) lists the catalogs — and the SQL a
`file:` catalog reads — in its explorer, completes `codes:` from the declared names, and
navigates from one to its declaration.

## Next

- [declarative-validation.md](declarative-validation.md) — field domains, where `codes:`
  is declared.
- [response-shaping.md](response-shaping.md) — `enrich:`, for the masters too large to
  hold.
- [internationalization.md](internationalization.md) — how a request's locale is
  resolved.
