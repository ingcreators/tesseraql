# Your first app

This tutorial takes you from an empty directory to a small, tested task tracker: a real
table, scaffolded CRUD pages, a hand-tuned search, and a validation rule — the whole
TesseraQL loop. You need the CLI installed and, ideally, Docker
([getting-started.md](getting-started.md)); if you want to see a finished app first, run
the [five-minute demo](five-minute-demo.md). Every step states what you should see before
you move on.

## Scaffold and run

```sh
tesseraql new tasktracker
cd tasktracker
docker compose up -d
tesseraql serve --app .
```

`tesseraql new` writes a runnable skeleton — config, a starter `items` table migration
(`db/migration/V1__create_items.sql`), a home page, a search route, and a smoke test
([scaffolding.md](scaffolding.md)). `docker compose up -d` starts the scaffolded local
PostgreSQL; `serve` applies `db/migration` on start and stays in the foreground. Expect:

```text
TesseraQL serving on port 8080. Press Ctrl+C to stop.
```

No Docker? Run `tesseraql serve --app . --embedded-db` instead — a real PostgreSQL inside
the process. `identity-schema --app .` below finds the running embedded database on its own
(via `work/embedded-db.jdbc`); the other database-touching commands (`migrate`,
`scaffold crud`, `test`) must be pointed at the JDBC URL `serve` prints, via `--jdbc-url`,
instead of reading the compose database from `--app .`'s config.

### First login

The identity store is not seeded. In a **second terminal** (leave `serve` running), create
the first administrator — same as [getting-started.md](getting-started.md), plus two roles
you will need shortly:

```sh
cd tasktracker
printf 'change-me' > admin.pw
tesseraql identity-schema --app . --admin-login admin --admin-password-file admin.pw \
    --admin-roles iam.admin,APP_READ,APP_WRITE
```

Expect `Applied the managed IAM schema (postgres)` and
`Seeded administrator 'admin' with roles [iam.admin, APP_READ, APP_WRITE]`. `iam.admin` is
the default admin role; `APP_READ`/`APP_WRITE` satisfy the `app.read`/`app.write` policies
(declared in `config/tesseraql.yml`) that guard every page you scaffold in this tutorial.
Roles are captured into the session when you sign in, so granting them up front saves a
sign-out later.

Now open `http://localhost:8080/_tesseraql/studio` and sign in as `admin` / `change-me`.
Studio shows the app: the Explorer lists the scaffolded routes. The starter home page is at
`http://localhost:8080/`.

## Create the tasks table

Write a second migration. The shape below follows the same conventions as the scaffolded
`V1` — an identity primary key, a `version` column for optimistic locking, audit columns,
and a named unique index — exactly what `scaffold crud` consumes
([transactional-writes.md](transactional-writes.md)).

Create `db/migration/V2__tasks.sql`:

```sql
create table tasks (
  id bigint generated always as identity primary key,
  title varchar(200) not null,
  note varchar(1000),
  done boolean not null,
  due_date date,
  version bigint not null,
  created_by varchar(200) not null,
  created_at timestamp not null,
  updated_by varchar(200) not null,
  updated_at timestamp not null
);

create unique index uq_tasks_title on tasks (title);
```

Apply it from the second terminal — no restart needed:

```sh
tesseraql migrate --app .
```

Expect `Applied 1 migration(s) for app tasktracker, datasource main (...)`. (Equivalent
paths: restarting `serve` auto-applies pending migrations on start, and Studio's migration
author can create a migration and apply it with **Migrate now**.)

## Scaffold the CRUD slice

```sh
tesseraql scaffold crud --app . --table tasks
```

The command introspects the live `tasks` table through the app's `main` datasource and
prints one `wrote` line per file — a list page, create form, detail/edit page, update and
delete commands, their 2-way SQL, and a test suite:

```text
web/tasks/            get.yml, list.view.yml, search.sql, frags.html
web/tasks/new/        get.yml, new.view.yml
web/tasks/create/     post.yml, insert.sql
web/tasks/{id}/       get.yml, select.sql, edit.view.yml
web/tasks/{id}/update/   post.yml, update.sql
web/tasks/{id}/delete/   post.yml, delete.sql
tests/tasks-crud-test.yml
```

Optionally add the page to the sidebar in `config/menu.yml`:

```yaml
  - label: Tasks
    href: /tasks
```

The server does **not** watch the filesystem — routes mount at start or when Studio applies
an edit — so restart to mount the new pages: `Ctrl+C` in the `serve` terminal, then
`tesseraql serve --app .` again.

Open `http://localhost:8080/tasks`. The routes declare `auth: browser` with the `app.read`
policy, so an unauthenticated browser is redirected to the login page and returned after
signing in; the `APP_READ` role you seeded satisfies the policy (a `403` here means the
signed-in user lacks it — re-run the `identity-schema` command above and sign out and back
in). You see an empty list with sortable column headers (id, title, note, done, due date),
a live search box, and a **New** button. Click **New**, create a task titled
`Write the tutorial`, and you are redirected to its detail page; the list now shows one row.

## Make it yours: edit the 2-way SQL

The scaffolded search is an exact `LIKE` match: typing `tut` in the search box finds
nothing. Open Studio's Explorer, navigate to `web/tasks/search.sql`, and make it a
case-insensitive contains-match. Before:

```sql
/*%if q != null && q != "" */
  and t.title like /* q */ 'sample'
/*%end*/
```

After:

```sql
/*%if q != null && q != "" */
  and lower(t.title) like lower('%' || /* q */ 'sample' || '%')
/*%end*/
```

The `/* q */ 'sample'` comment-plus-dummy-literal is the 2-way SQL bind
([two-way-sql.md](two-way-sql.md)): the file stays runnable as-is in a plain SQL tool,
and at runtime the literal becomes the bound `q` input.

Press **Apply** in Studio. The instant loop hot-reloads exactly the routes whose sources
changed — no restart — and the reload result names the rebuilt route. Go back to
`/tasks` and type `tut`: the row appears. (Editing the file on disk in your own editor is
equally fine: serve with `tesseraql serve --app . --watch` and every save under `web/`
hot-reloads the same way, no Apply needed; without `--watch`, disk edits are picked up on
the next restart or Studio apply. Hot reload covers `web/` routes — jobs and consumers
still need a restart.)

## Add a validation rule

Input constraints (`required`, `maxLength`, ...) already come from the scaffolded `input:`
block. Add a business rule ([declarative-validation.md](declarative-validation.md)): titles
must have at least three non-space characters. In Studio, open `web/tasks/create/post.yml`
and add below the `security:` block:

```yaml
validate:
  titleLength:
    rule: length(trim(params.title)) >= 3
    field: title
    code: invalid
```

Apply, then open `http://localhost:8080/tasks/new` and submit a task titled `ab`. The
command answers `422 Unprocessable Entity` and the form repaints with an inline error against
the Title field — `Invalid value.`, the built-in text for the `invalid` code. Declare a
`message:` key plus a `messages/` catalog entry for custom wording
([internationalization.md](internationalization.md)). Also try creating
`Write the tutorial` a second time: the scaffolded `errors.constraints` mapping turns the
`uq_tasks_title` unique-index violation into the same kind of field error
(`Already exists.`).

## Run the tests

From the second terminal (the compose database still up):

```sh
tesseraql test --app .
```

Expect `TesseraQL tests: 6 passed, 0 failed` — the skeleton's `tests/smoke-test.yml`
(two cases over the items search) plus the scaffolded `tests/tasks-crud-test.yml` (four
data-independent cases over the generated queries; they still pass after your `search.sql`
edit). Then lint:

```sh
tesseraql lint --app .
```

Expect `... 0 error(s)` and exit code 0. Both commands are what CI runs
([testing.md](testing.md)).

## Where to next

- [two-way-sql.md](two-way-sql.md) — the SQL dialect you just edited: binds, branches,
  embedded variables.
- [declarative-views.md](declarative-views.md) — the `list`/`form` view documents behind
  the scaffolded pages, and the customization ladder.
- [hypermedia-ui.md](hypermedia-ui.md) — the htmx + Hypermedia Components recipes
  (mutating forms, confirmed deletes) the pages are built from.
- [testing.md](testing.md) — declarative suites, coverage, the recorder.
- [deployment.md](deployment.md) — containers, health probes, observability.
