# 受注管理 — the Japanese-identifier gallery app

An order-management API whose schema speaks Japanese end-to-end, demonstrating the
identifier contract (docs/identifiers.md): the DDL column name **is** the YAML input
name, the 2-way SQL bind, the suite parameter, and the JSON key — one verbatim name
per concept, no conversion layer anywhere.

What it exercises:

- **Tables and columns**: `受注` (orders) with `受注番号`, `顧客名`, `状態`, `地域`,
  `金額` — unquoted identifiers, portable across the supported dialects.
- **Routes**: a JSON list with a `顧客名` search bind, and a detail route whose URL
  path parameter is `{受注番号}`.
- **A decision table**: `送料区分` (shipping class) over the `送料区分_rules` table,
  matching on `地域` with a wildcard default row.
- **A declarative suite**: `tests/受注-test.yml` runs the same queries and the
  decision with Japanese parameter names and row expectations.

Run it like any gallery app:

```bash
tesseraql run --app examples/juchu-kanri-app
```
