# Put an API and screens over an existing database

You already have the data. It is in a database someone else's system writes to, or an old one
whose front end has to go. You want an API, screens, or both — without migrating anything.
This guide is the reading order for that.

## The shape you are building

Routes over tables you did not create, respecting a schema you do not control, without the
framework taking ownership of it.

## The order to read

**1. Point at the database.**
[multi-datasource.md](multi-datasource.md) covers named datasources. Your existing database can
be `main`, or a second connector beside the one holding the framework's own tables. Decide this
first: writes stay on one connector, and that constrains what follows.

**2. Do not fight the naming.**
[identifiers.md](identifiers.md) is short and worth reading before anything else. The column
name in the DDL is the name everywhere — input name, bind, URL parameter — including legacy
naming and non-ASCII names. There is no mapping layer to configure, and no renaming to do.

**3. Generate the first slice.**
[scaffolding.md](scaffolding.md) reads a table and emits routes, SQL, pages, and a suite.
Against an existing table it is the fastest way to something working, and everything it emits
is ordinary source you can then edit.

**4. Shape the API to what callers need.**
[response-shaping.md](response-shaping.md) covers computed fields, nesting, and conditional
statuses, so the API shape is not forced to mirror the table shape.
[pagination.md](pagination.md) keeps large tables answerable.

**5. Be careful with writes.**
[transactional-writes.md](transactional-writes.md) covers multi-statement commands, and the
`expect: rowCount` check that refuses a stale update. When another system writes the same
tables, this matters more than usual.

**6. Guard what you expose.**
An existing database usually holds more than the caller should see.
[authentication.md](authentication.md) for who may call, [data-scoping.md](data-scoping.md) for
which rows, and field policies for which columns.

**7. Prove the queries against the real thing.**
[testing.md](testing.md) exercises SQL with coverage. A query written against a schema you do
not control deserves a test more than one you designed.

## What people usually get wrong

- **Migrating first.** You do not have to. The framework manages only its own `tql_*` tables,
  and the migration directory can stay empty for a database you did not create.
- **Renaming columns to look tidy.** The verbatim rule means the ugly legacy name costs you
  nothing. Renaming costs you a mapping layer.
- **Writing to a schema another system owns without locking.** Declare `expect: rowCount` and
  answer 409 rather than silently overwriting.
- **Exposing the table as the API.** Shape the response deliberately; you will not get to
  change it later.

## Next

- [multi-datasource.md](multi-datasource.md) — connecting to a database beside the main one.
- [identifiers.md](identifiers.md) — why legacy column names need no mapping.
- [scaffolding.md](scaffolding.md) — the first working slice from an existing table.
