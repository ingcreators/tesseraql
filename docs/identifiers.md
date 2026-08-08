# Identifiers

TesseraQL has one rule for names: **the column name is the name**. The identifier you
write in DDL is the same string you write as the YAML input name, the 2-way SQL bind,
the URL path parameter, the suite parameter, the template model key, and the JSON
response key. There is no case-conversion layer — nothing turns `order_date` into
`orderDate` between the database and the browser, so every layer can be grepped with
one string.

## What counts as an identifier

A table, column, alias, or field name is one Unicode letter or underscore followed by
Unicode letters, digits, or underscores:

```
identifier      = [letter or _] [letter, digit, or _]*
qualified name  = identifier [ "." identifier ]
```

That includes Japanese — `受注`, `顧客名`, `受注番号` are names like any other — and
every other script. It excludes anything that could read as SQL syntax: spaces,
quotes, dashes, semicolons, comment markers. Identifiers land in generated SQL
verbatim and unquoted, and this character class is what makes that safe.

A complete Japanese example ships in the gallery: `examples/juchu-kanri-app` defines
the `受注` table, routes at `/受注` and `/受注/{受注番号}`, a `送料区分` decision, and
a suite that exercises them with Japanese parameter names.

```sql
-- The bind name is the column name; the JSON key will be too.
select 受注番号, 顧客名, 状態
from 受注
where 受注番号 = /* 受注番号 */'J-1001'
```

## What stays ASCII

Names that address infrastructure rather than data keep the narrow ASCII shapes their
targets require: app names (they become URL prefixes, directories, and the per-app
migration-history table suffix), topic and environment-profile names, preference
keys, DuckDB extension and secret names, Prometheus label names, and SCIM attributes.

## Dialect notes

Every supported dialect accepts unquoted Unicode identifiers, and because CJK has no
letter case, the engines' case-folding differences cannot touch them. Two practical
limits to know:

| Engine | Identifier length limit |
| --- | --- |
| PostgreSQL | 63 **bytes** (about 21 kanji in UTF-8) — the tightest |
| Oracle | 128 bytes from 12.2 (30 bytes before) |
| MySQL | 64 characters |
| SQL Server | 128 characters |

Length is not linted — the database's own error is authoritative. On Oracle the
database character set must be Unicode (AL32UTF8, the modern default) for non-ASCII
identifiers.

## How Unicode names travel over HTTP

Browsers percent-encode non-ASCII URLs. The runtime decodes exactly the non-ASCII
percent-sequences of a request path before route matching, so `GET
/%E5%8F%97%E6%B3%A8` reaches the route declared at `web/受注/`. ASCII sequences such
as `%2F` deliberately stay encoded — decoding them would let an encoded slash cross a
path-segment boundary. Path parameters keep their declared names throughout your app
and the OpenAPI document; the HTTP router internally carries positional stand-ins for
names it cannot represent, and the request binder maps them back before your SQL sees
anything.

## Searching and sorting

Studio's documentation search indexes Japanese identifiers so that any substring run
finds them — `管理` finds `受注管理`. Message-catalog placeholders (`{顧客名}`),
view link templates, and workflow stamp columns all accept the same identifier
contract, so a Japanese app gets the same lint coverage — including the write-scope
guard — as an ASCII one.
