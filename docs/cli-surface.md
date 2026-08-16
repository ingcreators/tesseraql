# CLI surface

Status: **designed 2026-08-16** — nothing shipped. Blocks
[suite-architecture.md](suite-architecture.md) slice 3, which cannot name its development-loop
entry point until this decides what a command's arguments mean.

The CLI grew one command at a time and the options grew with it. Measured 2026-08-16 across the
26 commands in [reference-cli.md](reference-cli.md):

| Concept | Spelled | Problem |
| --- | --- | --- |
| Which application | `--app` on 26 commands, `--install-root` on `host` alone | two names |
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

### 1. One directory argument, and its contents decide what it is

`--app <dir>` names a directory. What runs is what the directory holds:

1. `catalog.json` at the root → an **install root**: the installed applications it catalogues.
2. `config/` or `web/` at the root → **one application**.
3. Otherwise, subdirectories **one level down** matching rule 2 → **a workspace**: every application
   in it.
4. Otherwise, an error naming all three shapes rather than a stack trace.

**Rule 2 is evaluated before rule 3, and the scan is one level deep.** Both halves of that sentence
are load-bearing, and the reason is not visible from the rule. Every real application home unpacks
the five bundled system applications into `work/apps/` — `account`, `auth-ui`, `iam-admin`,
`ops-console`, `studio` — and each of those *is* an application home. A recursive scan of an
ordinary application would mount the framework's own surfaces as if the operator had installed them.
Rule 2 catches the application first, and a one-level scan does not reach two levels down, so the
trap is disarmed twice. It will be reintroduced by the first person who makes the scan recursive to
be helpful, which is why it is written here rather than left in a comment.

Verified 2026-08-16: every application home in the tree has `config/` or `web/` — the seven
examples, the five bundled system applications, and the lint fixtures except `evil-app`, which is
deliberately empty.

### 2. `--app` is the only addressing flag, and narrowing is naming a narrower directory

`--install-root` is deleted. No `--suite` replaces it. The 26 commands that already say `--app` do
not change, and `host` stops being the exception.

Narrowing follows from Decision 1 rather than from a flag: `--app ./work` runs the suite,
`--app ./work/apps/orders` runs one application. [suite-architecture.md](suite-architecture.md)
Decision 19 asked that narrowing the development-tool MCP to a single application "stays available"
and be "a scoping flag, not a second mode". Under this rule it is not even a flag.

The alternative considered was `--app` for a path and an id-valued flag for narrowing inside a
suite — either a dual-meaning `--app` or a second word like `--only`. Both were rejected for the
same reason: they add vocabulary to express something the filesystem already expresses.

**What this costs, stated rather than discovered.** Commands that operate on exactly one application
— `package`, `scaffold`, `release-diff`, `verify` — must **refuse** a directory that resolves to
several. Silently picking the first is the failure this document exists to avoid.

The refusal is where this rule is either fine or annoying, so it is specified rather than left to
whoever writes it. It lists what it found and prints a command that works:

```
$ tesseraql package --app ./myworkspace
./myworkspace holds 3 applications; package works on one.

  tesseraql package --app ./myworkspace/orders
  tesseraql package --app ./myworkspace/billing
  tesseraql package --app ./myworkspace/reporting
```

A refusal that names the alternatives costs a second. One that says "expected a single application"
costs a directory listing and a guess.

**How much friction this actually adds, measured 2026-08-16: almost none, and not where it looks.**
None of those four commands knows what an install root is — no reference to `AppCatalog` or an
install root in any of them. They take one application home today and always have, so multi-application
resolution never engages for them. Against a workspace of source trees the change is
`--app ./orders` becoming `--app ./myworkspace/orders`: one path segment, naming a directory that
exists.

### 2a. Addressing an application inside a suite by id is deferred, with a trigger

The case where naming a directory is genuinely worse is an **install root**, where applications live
at `<installRoot>/<id>/<version>`. Saying `--app ./work/orders/1.2.0` means knowing the version;
saying `orders` would not.

It is not built, because **no command can do it today** — the four single-application commands do not
read catalogues at all, so this would be vocabulary for a capability that does not exist, which is
the thing Decision 2 declined to add.

**The trigger, so this is a deferral and not an omission:** the first single-application command that
needs to address an installed, catalogued application. At that point add id-based narrowing —
`--app <install-root> --only <id>` is the shape, and `AppCatalog.find(id)` already resolves it — and
not before.

### 3. `dev` replaces `serve`; `host` keeps production

[suite-architecture.md](suite-architecture.md) Decision 12 removes the gateway-less single
application, which is what `serve` is. Its development loop is not removed with it, so it moves to a
verb that says so.

The verb count does not change: `serve` + `host` becomes `dev` + `host`. What changes is that a
command pasted into a runbook says which one it is.

`dev` carries what `serve` carries — `--watch`, `--modules`, `--embedded-db`, `--embedded-db-port`,
`--embedded-db-version`, `--log-format`, `--log-level`, `--port` — over a suite rather than over one
application, through the gateway, because Decision 12 makes that the only shape there is. Under
Decision 1 a developer points it at a folder of source trees and gets a suite:

```bash
tesseraql dev --app ./myworkspace          # every application in the folder
tesseraql dev --app ./myworkspace/orders   # just this one
```

`host` keeps `--port`, `--http2`, `--trusted-proxies` and gains nothing from `dev`. An
`--embedded-db` in production is not a feature.

### 4. Options come in sets, and a command takes the whole set or none of it

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

### 5. Vocabulary is not flattened where the concepts differ

`--out` takes a file path. `--report-dir` takes a directory. Two names for two things is correct,
and collapsing them would be tidiness at the cost of meaning. They stay.

What gets fixed is a name that says the wrong thing: `migrate --app-name` names the application id
recorded in the schema history, and sits one character from `--app`, which names a directory.
It becomes `--schema-app`.

### 6. The rule is enforced by a test, because it will not survive review alone

Every decision here describes the surface a *future* command must join. A guard that reads the
picocli model and asserts the sets of Decision 4 turns "remember to add `--env`" into a build
failure, and it is cheap: the options are annotations on fields the test can read.

## Slices

| # | Slice |
| --- | --- |
| 1 | Directory resolution (Decision 1) as a shared resolver, with the `work/apps/` trap under test |
| 2 | `--install-root` → `--app` on `host`; single-application commands refuse a multi-application directory, listing the runnable alternatives |
| 3 | `serve` → `dev`, over a suite, through the gateway |
| 4 | The option sets (Decision 4) applied across every command, and the guard of Decision 6 |
| 5 | `--app-name` → `--schema-app`; `reference-cli.md` regenerated; `hosting.md` and `getting-started.md` rewritten to the new surface |

Slice 1 is first because slices 2 and 3 are both callers of it, and because it is where the trap
lives. Slice 4 is last of the mechanical ones because it touches the most files and conflicts with
everything.

## Out of scope

- **Renaming `--jdbc-url`, `--username`, `--password`.** They are precise and widely copied into
  scripts. The problem was that the set was incomplete, not that the words were wrong.
- **Subcommand renames beyond `serve` → `dev`.** `scaffold`, `governance` and `admission` are
  arguable and none of them is confusing enough to pay for.
- **A configuration file for CLI defaults.** A real want, and a separate decision: it interacts with
  profiles, with secrets, and with what a repository should commit.
- **Id-based narrowing inside a suite.** Deferred with a trigger — Decision 2a.

## Open questions

1. **Whether `dev` implies `--embedded-db`.** A zero-configuration `tesseraql dev` is attractive and
   would make the first five minutes shorter. Against it: a developer with a real database gets an
   embedded PostgreSQL started for no reason, and "why is it not reading my data" is an expensive
   first question. Recommendation: do not imply it; make `getting-started.md` show the flag.
2. **What `dev` does when the workspace holds no application.** An error is obvious; whether it
   should offer to scaffold one is not.
