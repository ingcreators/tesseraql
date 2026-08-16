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

What gets fixed is a name that says the wrong thing: `migrate --app-name` names the application id
recorded in the schema history, and sits one character from `--app`, which names a directory.
It becomes `--schema-app`.

### 7. The rule is enforced by a test, because it will not survive review alone

Every decision here describes the surface a *future* command must join. A guard that reads the
picocli model and asserts the sets of Decision 5 turns "remember to add `--env`" into a build
failure, and it is cheap: the options are annotations on fields the test can read.

## Slices

| # | Slice |
| --- | --- |
| 1 | The two resolvers (Decisions 1–3): one application, or the applications in a suite, with the refusals under test |
| 2 | `--install-root` → `--suite` on `host`; `--suite` added to `mcp` per Decision 19 |
| 3 | `serve` → `dev`, over `--app` or `--suite`, through the gateway |
| 4 | The option sets (Decision 5) applied across every command, and the guard of Decision 7 |
| 5 | `--app-name` → `--schema-app`; `reference-cli.md` regenerated; `hosting.md` and `getting-started.md` rewritten to the new surface |

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
