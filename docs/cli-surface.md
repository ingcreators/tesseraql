# CLI surface

Status: **designed 2026-08-16** — nothing shipped. Blocks
[suite-architecture.md](suite-architecture.md) slice 3, which cannot name its development-loop
entry point until this decides what a command's arguments mean.

The CLI grew one command at a time and the options grew with it. Measured 2026-08-16 across the
26 commands in [reference-cli.md](reference-cli.md):

| Concept | Spelled | Problem |
| --- | --- | --- |
| Which application | `--app` on 26 commands, `--install-root` on `host` alone | two names for what looks like one thing |
| Environment profile | `--env` on **2** of the ~16 commands that load a manifest | reachable only through `TESSERAQL_ENV` elsewhere |
| Plugin modules | `--modules` on 5 | absent from commands that compile routes |
| Database connection | `--jdbc-url`, `--username`, `--password` on 7; `--datasource` on 2 of those; `--realm` on 2 | the set is not a set |
| Where output goes | `--out` on 3, `--report-dir` on 2 | two names, and they are two concepts |
| Logging | `--log-format`, `--log-level` on `serve` alone | — |

None of this is a defect on its own. Together it means a reader cannot predict a command's options
from what the command does, which is the property a CLI is supposed to have.

**Pre-1.0, so this is a clean break.** No aliases, no deprecation cycle, no upgrade instructions —
a changelog line saying what changed and why is the whole obligation.

## Decisions

### 1. One flag, one meaning — so there are two flags

`--app <dir>` is **exactly one application**. `--suite <dir>` is **a directory holding several**.

An earlier draft of this document used `--app` for both and let the directory's contents decide
which. It was rejected in review, correctly: `--app` is singular, and a flag that sometimes denotes
one application and sometimes denotes six is a flag whose name is wrong half the time. A reader
cannot tell from `--app ./work` whether one thing or twelve things are about to start. The saving
was one word of vocabulary and the cost was ambiguity at every call site, which is the wrong trade.

So the distinction is carried by the flag, where the reader can see it, rather than by the
filesystem, where they cannot.

| Flag | Denotes | Accepted by |
| --- | --- | --- |
| `--app <dir>` | one application home | every command |
| `--suite <dir>` | several applications | the commands that *run* applications: `dev`, `host`, `mcp` |

**`--suite` is not new vocabulary.** [suite-architecture.md](suite-architecture.md) Decision 12
makes the suite the only deployment shape, and the campaign has been calling it that throughout.
The flag names a concept the system already has; it does not introduce one.

`--install-root` is deleted. It named an implementation detail — a directory with a catalogue in it
— where `--suite` names what the operator is running.

### 2. `--suite` accepts a catalogue or a folder of source trees, because both are a suite

- `catalog.json` at the root → the installed applications it catalogues. This is what
  `--install-root` meant.
- Otherwise, subdirectories **one level down** that are application homes → each of those.

That is one meaning — "the applications here" — recorded two ways. It is what lets a developer run
the shape they deploy without packaging anything first, which is
[suite-architecture.md](suite-architecture.md) slice 3's actual requirement:

```bash
tesseraql dev --suite ./myworkspace     # every application in the folder
tesseraql dev --app ./myworkspace/orders   # just this one
```

**A directory that is itself an application is refused, not scanned.** `--suite ./orders`, where
`./orders` has `config/` or `web/` at its root, answers *"that is one application — did you mean
`--app`?"*

That refusal is doing more work than it appears to. **Every real application home unpacks the five
bundled system applications into `work/apps/`** — `account`, `auth-ui`, `iam-admin`, `ops-console`,
`studio` — and each of those *is* an application home. A resolver that scanned an application
looking for children would mount the framework's own surfaces as if the operator had installed them.
Stopping at "this is one application" removes that path entirely rather than guarding it, which is
the second reason to prefer two flags over one: the single-flag draft had to reach the same safety
through rule ordering and a one-level scan depth, both of which the next person to make the scan
recursive would have undone.

Verified 2026-08-16: every application home in the tree has `config/` or `web/` — the seven
examples, the five bundled system applications, and the lint fixtures except `evil-app`, which is
deliberately empty.

### 3. Neither flag ever means an id, and narrowing is choosing the other flag

`--app` takes a path. `--suite` takes a path. Nothing takes an application id, so no flag's value
changes kind depending on a sibling flag — the failure mode Decision 1 exists to avoid, arrived at
from the other direction.

Narrowing a suite to one application is passing `--app` instead of `--suite`. It is not a mode, not
a filter, and not a third flag, which satisfies [suite-architecture.md](suite-architecture.md)
Decision 19's requirement that narrowing the development-tool MCP "stays available" as "a scoping
flag, not a second mode."

On the run-applications commands the two are **mutually exclusive and one is required**, the same
shape `tesseraql token` already uses for `--app` and `--url`.

**What this costs, stated rather than discovered.** A command that operates on one application
refuses `--suite` by not accepting it, which picocli reports without any code of ours. The
interesting refusal is `--suite` pointed at something that holds nothing, and `--app` pointed at a
folder of applications. Both list what they found and print a command that works:

```
$ tesseraql package --app ./myworkspace
./myworkspace is not an application; it holds 3.

  tesseraql package --app ./myworkspace/orders
  tesseraql package --app ./myworkspace/billing
  tesseraql package --app ./myworkspace/reporting
```

A refusal that names the alternatives costs a second. One that says "expected a single application"
costs a directory listing and a guess.

**How much friction this adds to the single-application commands, measured 2026-08-16: none.**
`package`, `scaffold`, `release-diff` and `verify` contain no reference to `AppCatalog` or to an
install root. They take one application home today and always have. They keep `--app`, unchanged,
and never see `--suite`.

### 3a. Addressing an application inside a suite by id is deferred, with a trigger

The case Decision 3 does not serve is an **install root**, where applications live at
`<installRoot>/<id>/<version>`. Saying `--app ./work/orders/1.2.0` means knowing the version; saying
`orders` would not.

It is not built, because **no command can do it today** — the four single-application commands do not
read catalogues at all, so this would be a flag for a capability that does not exist.

**The trigger, so this is a deferral and not an omission:** the first single-application command that
needs to address an installed, catalogued application. At that point add id-based narrowing —
`--suite <dir> --app-id <id>` is the shape, deliberately a *third* name rather than teaching `--app`
to take an id, and `AppCatalog.find(id)` already resolves it — and not before.

### 4. `dev` replaces `serve`; `host` keeps production

[suite-architecture.md](suite-architecture.md) Decision 12 removes the gateway-less single
application, which is what `serve` is. Its development loop is not removed with it, so it moves to a
verb that says so.

The verb count does not change: `serve` + `host` becomes `dev` + `host`. What changes is that a
command pasted into a runbook says which one it is.

`dev` carries what `serve` carries — `--watch`, `--modules`, `--embedded-db`, `--embedded-db-port`,
`--embedded-db-version`, `--log-format`, `--log-level`, `--port` — and takes `--app` or `--suite`,
running through the gateway either way, because Decision 12 makes that the only shape there is.

`host` takes `--suite` (or `--app`, for a single-application deployment) plus `--port`, `--http2`
and `--trusted-proxies`, and gains nothing from `dev`. An `--embedded-db` in production is not a
feature.

### 4a. `--port` is the front door, and an application's own `server.port` stops being one

On both `dev` and `host`, `--port` is the **gateway's** port. Every application runtime behind it
gets an ephemeral internal port, which is what `MultiAppHost` already does.

That is not a new rule for `host`, where `--port` has always meant this. It is a change for the
command that was `serve`, where `--port` overrode the application's own `server.port` and the
application *was* the front door. Under
[suite-architecture.md](suite-architecture.md) Decision 12 there is no shape in which it is.

**So `server.port` needs an answer, because the silent one is wrong.** Today an application
declaring `server.port: 9000` and started by `serve` answers on 9000; started through a host it gets
`freePort()` unconditionally and the declared value is discarded with nothing said.

**`server.port` keeps meaning exactly one thing: the port this application binds.** It is now an
internal port behind the gateway rather than an address anyone types, and that is the only thing
that changed about it.

An earlier draft of this decision resolved it as "the front door, when the suite holds exactly one
application" — which would have kept `dev --app ./orders` answering where `serve --app ./orders`
did. It is rejected for the reason Decision 1 gives about `--app`: it is one key meaning the
application's own port in one arrangement and the suite's front port in another, and a reader of
`server.port: 9000` could not tell which without counting the applications in the folder. The saving
was continuity for one command; the cost was a second meaning.

So:

| | Port | Set by |
| --- | --- | --- |
| The gateway | the address callers use | `--port`, default `8080` |
| Each application | internal, behind the gateway | its own `server.port`, default ephemeral |

`dev --app ./orders` with `server.port: 9000` therefore answers on **8080**, and the application
binds 9000 behind it. That is a behaviour change from `serve` and it is the honest one: under
Decision 12 the gateway is the address, and the key still does what its name says.

**Pinning an application's port is supported, because there is a real need for it and it is a
development need.** An ephemeral port cannot be pointed at — not by a debugger, not by a profiler,
not by a developer isolating whether the gateway is the problem, and not by Studio's API console,
which builds a URL against exactly this port. Declaring `server.port` makes the application
addressable directly, run after run.

It stays *ephemeral by default* rather than pinned by default, because in production nothing should
be addressing a runtime around the gateway, and a port that is hard to find is a mild discouragement
that costs nothing. And nothing needs to refuse a port collision between two applications: the JVM
already does, at bind time, naming the port.

**The gateway's port has no configuration key, only `--port`.** Under
[suite-architecture.md](suite-architecture.md) Decision 16 it is the host's setting, not an
application's, and a suite-level configuration file does not exist yet. Adding it there is a
separate decision and belongs with the host context object of slice 3.

**A consequence found while writing this, which is a pre-existing defect rather than a new one.**
Studio's API console builds `"http://127.0.0.1:" + port + path`. It carries no base path, so behind
a gateway it addresses `/api/users` on a runtime serving `/apps/<id>/api/users` and gets a 404. Its
`port <= 0` guard — "the API console needs a fixed `server.port`" — survives this decision and is
now *correct advice for the wrong reason*: declaring `server.port` does make the console work, but
the message describes an ephemeral port as a misconfiguration when it is the default. Neither is
created by this document; recorded here because slice 3 makes them universal, and because the
console is a surface a developer meets early.

### 4b. `--embedded-db` relocates the declared topology; it does not invent one

`dev` runs a suite, so `--embedded-db` has to answer a question `serve` never faced: where does each
application's data go when one PostgreSQL serves several of them.

**One server, one database, shared — and an application that wants separation writes
`currentSchema` in its own URL.** Nothing is derived from the application id and nothing is
injected that the application did not ask for.

The default is sharing because a suite is a team's interlocking applications
([suite-architecture.md](suite-architecture.md) Decision 12), and two of them reading one
`customers` table is the ordinary case rather than the dangerous one. Isolation is available to
whoever wants it, spelled the same way in development and in production.

**How it is applied: replace `db.main.*`, do not bypass the pool.** Today `--embedded-db` builds the
`main` pool directly from the override and never reads the declared configuration for it, which
loses whatever else was declared there and makes every tool that reads effective configuration
report the production URL while the runtime is on the embedded one. Instead, the embedded
coordinates are supplied through the environment source `AppConfig` already accepts — where they
outrank the configuration tree — so `${db.main.url}` resolves to them and everything referencing it
follows.

Two properties fall out of that, and both are the point:

- **Declared query parameters carry over.** `currentSchema`, `sslmode`, `ApplicationName` — the
  database name is replaced, the rest of the URL is not. So an application that chose a schema keeps
  it, which is what makes "write `currentSchema`" a real answer rather than advice.
- **Everything declared on `main` survives** — `maximumPoolSize` and its neighbours are read from
  configuration as usual, because the pool is no longer built from three fields.

**A backstop, because the placeholder is a convention rather than a contract.** `db.main.*` is
written by the scaffolder and followed by every example, but the runtime reads only
`tesseraql.datasources.main.*`; an application may spell its URL literally. So after resolution, if
`main` does not point at the embedded server, the pool-level override applies as it does today —
and says so in one line. That keeps the case this feature must never get wrong, which is a
development command connecting to production, while leaving the good path unpenalised.

An earlier draft derived a database per application from the declared name, and another injected
`currentSchema=<appId>`. Both are rejected for the same reason: they invent a separation the
applications did not ask for, and they make development differ from production in exactly the
dimension [suite-architecture.md](suite-architecture.md) Decision 12 exists to keep identical.

**The framework datasource is not part of this.** `security` is suite-wide, so the host supplies one
coordinate for every runtime ([suite-architecture.md](suite-architecture.md) Decision 16) rather
than deriving it from any application's URL. An application's `currentSchema` must not reach it: a
per-application session store is a suite where signing in does not carry.

Schema creation needs nothing from the author. Measured 2026-08-16 against PostgreSQL 16: Flyway
creates an absent `currentSchema` itself, and each application already migrates under its own
history table `tql_schema_history_<app>`, so `currentSchema=hd` on a shared database is the whole
configuration.

### 5. Options come in sets, and a command takes the whole set or none of it

Three sets, each defined by a capability rather than by a list:

| Set | Options | Taken by |
| --- | --- | --- |
| Configuration | `--env` | every command that loads a manifest |
| Compilation | `--modules` | every command that compiles routes |
| Connection | `--jdbc-url`, `--username`, `--password`, `--datasource` | every command that opens a database |

The membership test is a property of the code, not a judgement: a command loads a manifest or it
does not. That is what makes the rule checkable, and a test should check it rather than a reviewer.

`--env` is the one whose absence is closest to a defect. `tesseraql lint` cannot lint what the
production profile resolves to, because the profile can only be set through `TESSERAQL_ENV` or
`-Dtesseraql.env`. The escape hatch exists, which is why this is an inconsistency and not an
outage — but a flag that exists on `serve` and not on `lint` reads as "lint does not have profiles".

`--realm` stays on `test` and `coverage` only. It is not part of the connection set: it names an
identity realm to run as, which is a property of those two commands and of nothing else.

### 6. Vocabulary is not flattened where the concepts differ

`--out` takes a file path. `--report-dir` takes a directory. Two names for two things is correct,
and collapsing them would be tidiness at the cost of meaning. They stay.

`migrate --app-name` was going to be renamed here, for sitting one character from `--app` while
naming something else entirely. **Investigating the rename found that the flag should not exist**,
and that the defect it papers over is larger than the CLI — so it is deleted rather than renamed,
and the rest belongs to its own change.

The flag keys the Flyway history table, `tql_schema_history_<value>`. Three entry points derive that
value three different ways: the runtime from `tesseraql.app.name`, `tesseraql migrate` from the
**application directory name**, and the `tesseraql:migrate` Maven goal from
**`${project.artifactId}`**. Measured 2026-08-16 across the seven examples, the directory name and
the application name **never** agree — `helpdesk-app` against `helpdesk` — so migrating from the CLI
writes a history the runtime then ignores, and re-runs everything under its own.

Once the value is derived in one place, the flag's only remaining power is writing migration history
under a key the runtime will never read, which is the failure it was compensating for. That is a
surface to remove rather than rename ([suite-architecture.md](suite-architecture.md) Decision 21).
The replacement is declarative, so every entry point picks it up without being told:
`tesseraql.migrations.historyName`, winning over `tesseraql.app.name` when set — which is also the
escape hatch for the identifier-length limit, since the derived name is refused rather than silently
truncated when it overflows the dialect's maximum.

The Maven goal's `tesseraql.appName` parameter goes the same way, and the goal gains the
configuration load it does not do today.

### 7. The sets are `@Mixin`s, and the test guards their shape rather than their membership

The first draft of this decision promised a guard that reads the picocli model and asserts
membership — "this command loads a manifest, so it must declare `--env`". **That test cannot be
written**, and the reason is worth recording because it looks writable.

Measured 2026-08-16: `lint`, `admission` and `release-diff` never mention `ManifestLoader`. They
call `AppLinter.lint(app)`, `AdmissionProfile.check(app)` and `ReleaseDiff.between(baseline, app)`,
each of which resolves configuration inside. A guard that inferred capability from the command class
would have decided those three need no `--env`, which is exactly backwards — `lint` is the command
whose missing `--env` is closest to a defect.

So the split is:

- **Membership is declared, in one line.** Each set is a picocli `@Mixin` — `ConfigOptions`,
  `CompileOptions`, `ConnectionOptions` — and a command mixes in what it needs. Whether it needs one
  stays a review question, made cheap by being visible at the top of the class instead of spread
  across four `@Option` fields.
- **The test guards shape.** No command may declare `--env`, `--modules`, `--jdbc-url`,
  `--username`, `--password` or `--datasource` as its own field; those names belong to the mixins.
  That is checkable from the picocli model, and it is what stops the sets from drifting apart again
  one command at a time — which is how they drifted in the first place.

A guard that cannot answer the interesting question is still worth having when it answers the
question that actually decayed.

## The complete mapping

Every command, and what these decisions do to it. `+set` means the command joins a Decision 5 set
and gains its options.

| Command | Change |
| --- | --- |
| `serve` | **becomes `dev`**; `--app` gains `--suite` beside it; `+config` (already had `--env`) |
| `host` | `--install-root` → **`--app` / `--suite`**; **`--mode` deleted** with independent hosting (`suite-architecture.md` Decision 12); keeps `--port`, `--http2`, `--trusted-proxies`; `+config` |
| `mcp` | gains **`--suite`** — `suite-architecture.md` Decision 19 makes the development-tool MCP span the suite; `--read-only` becomes a property of the server, not of an application; `+config` |
| `new` | `--dir` → **`--app`**. It is the directory that becomes an application home, and every other command calls that `--app` |
| `migrate` | `--app-name` **deleted**, not renamed — see Decision 6; `+config` |
| `scaffold` | `+config`, `+connection` (has three of four; gains `--datasource`) |
| `test` | `+config`, `+connection` (gains `--datasource`) |
| `coverage` | `+config`, `+connection` (gains `--datasource`) |
| `job` | `+config`, `+connection` (gains `--datasource`) |
| `identity-schema` | `+config`, `+connection` (gains `--datasource`) |
| `schema` | `+config` (connection already complete) |
| `lint` | `+config` — the command whose missing `--env` is closest to a defect |
| `routes`, `symbols`, `generate`, `governance`, `admission`, `verify`, `package`, `release-diff`, `modules`, `duckdb` | `+config` |
| `token` | unchanged — `--app` / `--url` already exclusive, `--env` already present |
| `embedded-db` | unchanged — takes no options |

**`--modules` membership is settled in slice 4, per command, by whether it compiles routes**, and is
deliberately not guessed here. Measured today: `lint`, `test`, `coverage`, `job`, `duckdb`, `routes`
and `mcp` reach route compilation; `serve` already carries the flag. The rest are read-only over
sources or over a database and probably do not, but "probably" is not a mapping and the slice that
adds the mixin can answer it per command in one line.

Nothing above removes an option except `--install-root`, which is renamed, and `--mode`, which loses
its subject. Everything else is addition or a rename.

## Slices

| # | Slice |
| --- | --- |
| 1 | The two resolvers (Decisions 1–3): one application, or the applications in a suite, with the refusals under test |
| 2 | `--install-root` → `--suite` on `host`; `--suite` added to `mcp` per Decision 19 |
| 3 | `serve` → `dev`, over `--app` or `--suite`, through the gateway; `--port` as the front door, and `MultiAppHost` honouring a declared `server.port` instead of always calling `freePort()` (Decision 4a) |
| 3a | `--embedded-db` for a suite (Decision 4b): the coordinates supplied through the environment source, the declared query string carried over, the pool-level backstop and its one line |
| 4 | The three `@Mixin` sets (Decision 5) applied across every command, `--modules` membership settled per command, and the shape guard of Decision 7 |
| 5 | `new --dir` → `--app`; `reference-cli.md` regenerated; `hosting.md` and `getting-started.md` rewritten to the new surface |

**The `--app-name` deletion is not in these slices.** It depends on the history-key change of
Decision 6, which fixes a defect that bites `serve` and every scaffolded application today and has
no reason to wait for a suite. It ships on its own, before or beside slice 1.

Slice 1 is first because slices 2 and 3 are both callers of it, and because it is where the
application-versus-suite refusals live. Slice 4 is last of the mechanical ones because it touches
the most files and conflicts with everything.

## Out of scope

- **Renaming `--jdbc-url`, `--username`, `--password`.** They are precise and widely copied into
  scripts. The problem was that the set was incomplete, not that the words were wrong.
- **Subcommand renames beyond `serve` → `dev`.** `scaffold`, `governance` and `admission` are
  arguable and none of them is confusing enough to pay for.
- **A configuration file for CLI defaults.** A real want, and a separate decision: it interacts with
  profiles, with secrets, and with what a repository should commit.
- **Id-based narrowing inside a suite.** Deferred with a trigger — Decision 3a.

## Open questions

1. **Whether `dev` implies `--embedded-db`.** A zero-configuration `tesseraql dev` is attractive and
   would make the first five minutes shorter. Against it: a developer with a real database gets an
   embedded PostgreSQL started for no reason, and "why is it not reading my data" is an expensive
   first question. Recommendation: do not imply it; make `getting-started.md` show the flag.
2. **What `dev --suite` does when the folder holds no application.** An error is obvious; whether it
   should offer to scaffold one is not.
3. **Studio's API console behind a gateway** — Decision 4a. It needs the base path and it needs to
   stop treating an internal port as a misconfiguration. Whether it should address the gateway
   instead of the runtime is the real question, and it is a Studio decision rather than a CLI one:
   `suite-architecture.md` slice 8 is where a suite-level Studio is designed, and this may belong
   there rather than being patched twice.
