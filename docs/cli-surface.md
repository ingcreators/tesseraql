# CLI surface

Status: **designed 2026-08-16** — nothing shipped. Blocks
[stack-architecture.md](stack-architecture.md) slice 3, which cannot name its development-loop
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

### 1. Two flags, and only one of them runs anything

`--app <dir>` is **exactly one application home**, for the commands that operate *on* an application
— `package`, `lint`, `migrate`, `scaffold`, `release-diff`, `verify` and the rest. `--stack <dir>`
is **the applications to run**, for `dev`, `host` and `mcp`. **The running commands do not accept
`--app`.**

An earlier draft used `--app` for both and let the directory's contents decide which. It was
rejected in review, correctly: `--app` is singular, and a flag that sometimes denotes one
application and sometimes denotes six is a flag whose name is wrong half the time.

| Flag | Denotes | Accepted by |
| --- | --- | --- |
| `--app <dir>` | one application home | the commands that operate on an application |
| `--stack <dir>` | the applications to run | `dev`, `host`, `mcp` |

**Amended 2026-08-16, and this is a reversal.** This decision first gave the running commands
*both* flags, `--app` meaning "this directory is the deployment". Reviewed against what it cost, it
was wrong, and the reasons compound:

- **It gave one application two addresses.** `host --app ./orders` served at the origin root and
  `host --stack ./work` served the same application at `/apps/orders`. A team developing with one
  flag and deploying with the other changes every URL the application emits — a divergence between
  development and production in exactly the dimension
  [stack-architecture.md](stack-architecture.md) Decision 12 exists to remove.
- **It derived an address from a flag**, immediately after Decision 12's implementation established
  that an application's address is *declared* in its catalogue entry rather than derived from a
  constant. Deriving it from a flag instead is the same shape one level up.
- **It cost Decision 22 an option.** `--app` names a directory that is an application's own tree,
  so there is nowhere in it to put a stack's settings file, and the settings had to be reachable
  through a `--stack-config <file>` that existed for no other reason.
- **It doubled the resolver's refusals.** `AppDirectory` had to refuse each flag's directory in the
  other's shape and cross-reference the two.

None of these is fatal alone. Together they are four costs paid so that a single-application
deployment can have an address it can now simply declare.

**`--stack` is not new vocabulary.** [stack-architecture.md](stack-architecture.md) Decision 12
makes the stack the only deployment shape, and the campaign has been calling it that throughout. A
stack holding one application is still a stack — the flag's plural is not a claim about arity — but
it is still a *directory holding* that application, which is Decision 2.

`--install-root` is deleted. It named an implementation detail — a directory with a catalogue in it
— where `--stack` names what the operator is running.

### 2. `--stack` takes a directory that *holds* applications, and nothing else

- `catalog.json` at the root → the installed applications it catalogues. This is what
  `--install-root` meant.
- Otherwise, subdirectories **one level down** that are application homes → each of those.

One sentence, recorded two ways: *the applications here*.

```bash
tesseraql dev --stack ./myworkspace                      # every application in the folder
tesseraql dev --stack ./myworkspace --app-name orders    # just this one (Decision 3)
```

**A directory that is itself an application is refused, not scanned**, and the refusal names the
directory that would have worked:

```
$ tesseraql dev --stack ./work/orders
./work/orders is one application, not a stack.

  tesseraql dev --stack ./work --app-name orders
```

That refusal is doing two jobs. **The first is the `work/apps/` trap.** Every real application home
unpacks the five bundled framework surfaces into `work/apps/` — `account`, `auth-ui`, `iam-admin`,
`ops-console`, `studio` — and each of those *is* an application home. A resolver that scanned an
application looking for children, or scanned recursively, would offer the framework's own surfaces
as if the operator had installed them. Recognising the application and stopping removes that path
entirely rather than guarding it.

**The second is that a stack has to have somewhere to put `stack.yml`.**

**Amended twice on 2026-08-16, and this is where the second amendment landed.** An intermediate
version accepted an application home as "a stack of one", to save a single-application repository
one directory. It was wrong, and the way it was wrong is worth keeping because the argument for it
sounded like simplification:

- `stack.yml` lives in the stack's directory ([stack-architecture.md](stack-architecture.md)
  Decision 22), and an application home is not one — a stack file there would ship inside the
  application's package. So a stack of one could hold **no stack settings at all.**
- The reply written at the time was that a stack of one has no cross-application divergence to
  prevent, so it needs none. **That applied half of Decision 16's rule.** The rule has two limbs — a
  setting belongs to the host when only the host can know it, *or* when divergence between
  applications fails silently — and **the external origin is the first limb.** One application still
  cannot know its own external origin, and Decision 18's MCP `resource` and the authorization
  server's `issuer` both need it. The shape could not supply it and never would.
- Defending it then cost a second mechanism: a check for a `stack.yml` in the *parent* directory, to
  refuse the case where narrowing by path would silently drop the stack's settings. A shape that
  needs a guard to stop it being used the obvious way is a shape, not a saving.

Removing it removes all three. Every stack has a directory, so `stack.yml` is always possible, the
parent check is unnecessary because the invocation it guarded is refused outright, and Decision 22
loses a special case.

**What it costs is one `mkdir`, once, for a repository holding a single application** — and for
anything `tesseraql new` created, not even that, because `new` already places the application inside
a parent (Decision 8). The layout it asks for is the layout every other stack already has:

```
myrepo/
  stack.yml
  orders/
    config/
    web/
```

Measured 2026-08-16: the seven examples are already laid out this way — `examples/` holds seven
application homes one level down — so `tesseraql dev --stack ./examples` needs nothing new.

Verified 2026-08-16: every application home in the tree has `config/` or `web/` — the seven
examples, the five bundled framework surfaces, and the lint fixtures except `evil-app`, which is
deliberately empty.

### 3. Neither flag ever means a path *and* a name, and narrowing a stack is `--app-name`

`--app` takes a path. `--stack` takes a path. Neither ever takes an application's name, so no flag's
value changes kind depending on a sibling flag.

**Narrowing a stack to one of its applications is `--stack ./work --app-name orders`.** The stack
directory is still named, so `stack.yml` is read and the application starts at `/apps/orders` — the
address it has when the whole stack runs. Narrowing changes *how many* runtimes start and nothing
else, which is what [stack-architecture.md](stack-architecture.md) Decision 19 asks for when it
requires narrowing the development-tool MCP to stay "a scoping flag, not a second mode."

**Amended twice on 2026-08-16, and each amendment corrected the previous one.** The original made
narrowing `--app` instead of `--stack`, which changed the application's address while narrowing, so
the narrow case and the whole case disagreed about where the application answered. The replacement
made it `--stack ./work/orders` — the member's own directory — which fixed the address and **broke
the settings**, since `./work/stack.yml` is not reached up for. Decision 2 now refuses that path
outright, and narrowing is a flag whose value is the name the application declares.

**`--app-name` is the same word as the configuration key**, `tesseraql.app.name`, which Decision 6
makes the single spelling of an application's identity. A reader who wrote the name knows what to
pass, which is the only property this flag has to have.

**One cost, named rather than discovered:** `migrate --app-name` was deleted by the change Decision
6 describes, and this reuses the spelling in the same release for a different meaning. The deletion
was of a flag that let an operator hand-correct a derived value; this one selects an application by
the name its author declared. Same word, and correctly so — but the changelog says both things
rather than leaving a reader to notice a flag reappearing.

**What this costs on the single-application commands, measured 2026-08-16: nothing.** `package`,
`scaffold`, `release-diff` and `verify` contain no reference to `AppCatalog` or to an install root.
They take one application home today and always have. They keep `--app`, unchanged, and never see
`--stack`. The interesting refusal is `--app` pointed at a folder of applications, which lists what
it found and prints a command that works:

```
$ tesseraql package --app ./myworkspace
./myworkspace is not an application; it holds 3.

  tesseraql package --app ./myworkspace/orders
  tesseraql package --app ./myworkspace/billing
  tesseraql package --app ./myworkspace/reporting
```

A refusal that names the alternatives costs a second. One that says "expected a single application"
costs a directory listing and a guess.

### 3a. Addressing an application by name on the single-application commands is still deferred

Decision 3 gives `--app-name` to the commands that *run* applications, where the need is immediate.
The case still not served is a **single-application command** against an install root, where
applications live at `<installRoot>/<name>/<version>`: saying `--app ./work/orders/1.2.0` means
knowing the version, and saying `orders` would not.

It is not built, because **no such command can do it today** — `package`, `scaffold`,
`release-diff` and `verify` do not read catalogues at all, so this would be a flag for a capability
that does not exist.

**The trigger:** the first single-application command that needs to address an installed, catalogued
application. The flag is already named and already implemented next door, so it is
`--stack <dir> --app-name <name>` there too, with `AppCatalog.find(name)` resolving it — and not
before.

### 4. `dev` replaces `serve`; `host` keeps production

[stack-architecture.md](stack-architecture.md) Decision 12 removes the gateway-less single
application, which is what `serve` is. Its development loop is not removed with it, so it moves to a
verb that says so.

The verb count does not change: `serve` + `host` becomes `dev` + `host`. What changes is that a
command pasted into a runbook says which one it is.

`dev` carries what `serve` carries — `--watch`, `--modules`, `--embedded-db`, `--embedded-db-port`,
`--embedded-db-version`, `--log-format`, `--log-level`, `--port` — and takes `--stack` with an
optional `--app-name`, running
through the gateway, because Decision 12 makes that the only shape there is and Decision 1 makes
`--stack` the only way to name what runs.

`host` takes `--stack` plus `--port`, `--http2` and `--trusted-proxies`, and gains nothing from
`dev`. An `--embedded-db` in production is not a feature.

### 4a. `--port` is the front door, and an application's own `server.port` stops being one

On both `dev` and `host`, `--port` is the **gateway's** port. Every application runtime behind it
gets an ephemeral internal port, which is what `MultiAppHost` already does.

That is not a new rule for `host`, where `--port` has always meant this. It is a change for the
command that was `serve`, where `--port` overrode the application's own `server.port` and the
application *was* the front door. Under
[stack-architecture.md](stack-architecture.md) Decision 12 there is no shape in which it is.

**So `server.port` needs an answer, because the silent one is wrong.** Today an application
declaring `server.port: 9000` and started by `serve` answers on 9000; started through a host it gets
`freePort()` unconditionally and the declared value is discarded with nothing said.

**`server.port` keeps meaning exactly one thing: the port this application binds.** It is now an
internal port behind the gateway rather than an address anyone types, and that is the only thing
that changed about it.

An earlier draft of this decision resolved it as "the front door, when the stack holds exactly one
application" — which would have kept `dev ./orders` answering where `serve --app ./orders` did. It
is rejected because it is one key meaning the application's own port in one arrangement and the
stack's front port in another, and a reader of `server.port: 9000` could not tell which without
counting the applications in the folder. The saving was continuity for one command; the cost was a
second meaning.

So:

| | Port | Set by |
| --- | --- | --- |
| The gateway | the address callers use | `--port`, default `8080` |
| Each application | internal, behind the gateway | its own `server.port`, default ephemeral |

`dev --stack ./orders` with `server.port: 9000` therefore answers on **8080**, and the application
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
[stack-architecture.md](stack-architecture.md) Decision 16 it is the host's setting, not an
application's, and a stack-level configuration file did not exist when this was written. One does
now — **Decision 22's `stack.yml`** — and the port deliberately **stays out of it**. That file
carries the settings whose divergence fails silently; a wrong port fails at bind, naming the port.

**A consequence found while writing this, which is a pre-existing defect rather than a new one.**
Studio's API console builds `"http://127.0.0.1:" + port + path`. It carries no base path, so behind
a gateway it addresses `/api/users` on a runtime serving `/apps/<id>/api/users` and gets a 404. Its
`port <= 0` guard — "the API console needs a fixed `server.port`" — survives this decision and is
now *correct advice for the wrong reason*: declaring `server.port` does make the console work, but
the message describes an ephemeral port as a misconfiguration when it is the default. Neither is
created by this document; recorded here because slice 3 makes them universal, and because the
console is a surface a developer meets early.

### 4b. `--embedded-db` relocates the declared topology; it does not invent one

`dev` runs a stack, so `--embedded-db` has to answer a question `serve` never faced: where does each
application's data go when one PostgreSQL serves several of them.

**One server, one database, shared — and an application that wants separation writes
`currentSchema` in its own URL.** Nothing is derived from the application id and nothing is
injected that the application did not ask for.

The default is sharing because a stack is a team's interlocking applications
([stack-architecture.md](stack-architecture.md) Decision 12), and two of them reading one
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
dimension [stack-architecture.md](stack-architecture.md) Decision 12 exists to keep identical.

**The framework datasource is not part of this.** `security` is stack-wide, so the host supplies one
coordinate for every runtime ([stack-architecture.md](stack-architecture.md) Decision 16) rather
than deriving it from any application's URL. An application's `currentSchema` must not reach it: a
per-application session store is a stack where signing in does not carry.

**Amended 2026-08-17.** "Not part of this" was read as "left alone", and left alone it collides with
the sentence above it. With no `stack.yml`, Decision 16 falls back to each runtime's own
`tesseraql.framework.datasource` — `main` — so the `currentSchema` this decision recommends makes
two applications resolve two different coordinates, and the disagreement check refuses the whole
development stack. So `--embedded-db` **supplies** the framework datasource: the same embedded
server's shared database, with no schema qualifier. That is not derived from an application, which
is what this paragraph actually prohibits.

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

### 6. Vocabulary is not flattened where the concepts differ — and *is* where they do not

`--out` takes a file path. `--report-dir` takes a directory. Two names for two things is correct,
and collapsing them would be tidiness at the cost of meaning. They stay.

**An application's identity is spelled `name`, everywhere, and `id` is deleted as a synonym.** This
document opens by naming "two names for what looks like one thing" as the defect it exists to fix,
and the identity of an application was an instance of it that the first draft did not see.

Measured 2026-08-16:

| Surface | `name` | `id` |
| --- | --- | --- |
| Configuration keys | `tesseraql.app.name` (47), `tesseraql.apps.<name>.*` | none — `tesseraql.app.id` does not exist |
| Java identifiers in main code | `appName` (358) | `appId` (66) |
| Permissions, MCP server, outbox scoping, job ownership | `ops.app.<name>` | — |
| The catalogue | — | `InstalledApp.id`, `catalog.json`'s `"id"` |

**And the two are the same string by construction, in every case.** `AppInstaller` reads
`config.getString("tesseraql.app.name")` and stores it as the catalogue entry's `id`; `AppDirectory`
synthesises entries the same way for an uncatalogued source tree. There is no case in the tree where
an application's id and its name differ, and none is wanted: Decision 6's own rule for the migration
history is that the identity is derived in *one* place, and an application that could be addressed
by two different strings would reopen exactly that defect.

So `id` goes: `InstalledApp.id` → `name`, `catalog.json`'s `"id"` → `"name"`,
`AppCatalog.find(id)` → `find(name)`, `MultiAppHost.appIds()` → `appNames()`, and the `appId`
parameters in the seven files that carry them. It is a catalogue format change, which pre-1.0 costs
a changelog line and nothing else.

The direction is not arbitrary. `name` is what the application's author writes and what
[stack-architecture.md](stack-architecture.md) made **required** rather than defaulted, on the
grounds that it is an identity — outbox claim scoping, cluster job claim keys, `ops.app.<name>`, the
MCP server name, and now the stack address. `id` appears only in artefacts derived from it.

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
surface to remove rather than rename ([stack-architecture.md](stack-architecture.md) Decision 21).
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

### 7a. The deployment unit is a **stack**, because "suite" already means a test file here

Raised in review as *"is `suite` even a common word for this?"* It is not, and inside this
repository it is already taken.

**Industry usage.** A set of applications deployed and torn down as one unit is a **stack** —
CloudFormation, Docker Swarm's `docker stack deploy`, Pulumi, CDK — or a **project**, in Docker
Compose. A directory holding several packages is a **workspace** — Nx, npm, pnpm, Cargo, Bazel — or
a **solution**, in .NET. Kubernetes calls the boundary a **namespace**, and reserves `Deployment`
for a single workload. "Suite" in this industry means a **product bundle** (an office suite) or a
**test suite**. It does not name a deployment unit anywhere.

**And the second sense is this project's own.** Measured 2026-08-16: of the occurrences of the word
in main source, 186 are the test sense and 33 the deployment sense — `TestSuite`, `RouteSuite`,
`SuiteCoverage`, `TestSuiteLoader`, `suiteName`, `loadSuites`, and a `SuiteContext` that lives in
`tesseraql-test-core`. The deployment sense is the newcomer, introduced by this campaign.

**The published glossary settles it.** `glossary.md` reads:

> **suite** — a declarative test file under `tests/`, exercising routes, SQL, security, or …

and `testing.md` teaches the same word the same way. A user who read either and then met
`tesseraql host --suite` would be reading one word for two things in one documentation set — the
defect this document opens by naming.

So the deployment unit is a **stack**: `--stack <dir>`, `stack.yml`, and
[stack-architecture.md](stack-architecture.md). `stack` reads in both places the concept has to
work — `dev --stack ./work` and `host --stack /opt/apps` — which `workspace` does not, and it
carries no meaning in this repository yet.

**Renamed here, and the rest is owed with the code.** The design documents move now, because they
describe the target. The documents that describe the CLI as it *ships today* — `hosting.md`,
`deployment.md`, `reference-cli.md` — keep saying `--suite` until `HostCommand` changes, since a
document that describes a flag that does not exist is worse than one using a word that is about to.
Owed with that change: `SuiteRelay` → `StackRelay`, `SuiteModeIntegrationTest`, the `--suite` flag
shipped in #832, and the prose in `app-isolation-model.md`, `base-path.md`, `audit-hardening.md`
and `token-issuance.md`. `TestSuite` and everything around it keeps the word, which is the point.

### 8. `new` already creates the layout, so its flag is `--stack` and it writes no stack file

**Measured 2026-08-16, and it corrects this document.** The mapping table first said `new --dir`
becomes `--app`, on the grounds that "it is the directory that becomes an application home". That is
wrong about the code. `NewCommand` takes the application's name as a positional argument and
`--dir` as the **parent**:

```java
@Parameters(index = "0", description = "The app name ([a-z][a-z0-9-]*); also the directory.")
String appName;
@Option(names = {"--dir"}, description = "Parent directory to create the app in (default: .).")
Path dir = Path.of(".");
```

`home = dir.resolve(appName)`. Renaming that to `--app` would make one command's `--app` mean *the
parent of* an application home while twenty-five others mean the home itself — the one-flag-two-kinds
failure Decision 1 exists to prevent, introduced by the rename meant to tidy it.

**It becomes `--stack`.** A directory that a new application is created *inside* is, by definition, a
directory holding applications. So the same word carries the same directory from creation to
running:

```bash
tesseraql new orders --stack ./work    # creates ./work/orders/
tesseraql dev --stack ./work           # runs it
```

**And the default already produces the right shape.** `--dir` defaults to `.`, so `tesseraql new
orders` leaves the current directory holding one application home one level down — a stack, with no
further step. Decision 2's cost was quoted as "one `mkdir` for a single-application repository"; the
measurement says it is **zero** for anything created by `new`.

**`new` does not write `stack.yml`**, for three reasons that are the same reason:

- **It has no required content.** Decision 22 puts settings in that file when divergence between
  applications fails silently, or when only a host can know the value. A workspace being scaffolded
  has one application and no host in front of it yet, so a generated file would be entirely
  commented out — a file that says nothing, which is a cost with no reader.
- **It is outside the directory the command was told to create.** `new orders` writing `./stack.yml`
  as well as `./orders/` is a surprise, and surprises in a scaffolder are expensive because they are
  discovered later, in someone else's checkout.
- **It is shared.** The second `tesseraql new` into the same stack would meet a file the first one
  wrote, and would have to decide whether to merge, skip or refuse — three answers to a question
  that does not need asking.

What is owed instead is discoverability, and the command already has a place for it. Its "Next
steps" are rewritten, which they need anyway: they currently `cd` into the application and run
`serve --app .`, and after Decision 2 that directory is not a stack, so the obvious next command
would be refused.

```
Created app 'orders' at /home/dev/work/orders (14 files).

Next steps:
  # point orders/config/application.yml at your database, then
  tesseraql dev --stack .
  tesseraql scaffold crud --app ./orders --table items

This directory is now a stack. Add ./stack.yml when the applications in it need
a shared session store, an external URL, or an issuer (docs/hosting.md).
```

### 9. An omitted `--stack` is found the way `cargo` finds a workspace — one level, never further

`cd work/orders && tesseraql dev` is the muscle memory every toolchain trains — `npm run dev`,
`cargo build`, `git status` all run from inside the thing being worked on. Requiring
`dev --stack ..` from there is a small persistent toll on the most-typed command in the product.

**On `dev` and `mcp`, when `--stack` is not given:**

- the working directory is stack-shaped (holds applications, or `catalog.json`) → it is the stack;
- the working directory is an application home and its **parent** is stack-shaped → the parent is
  the stack;
- anything else → the refusal Decision 2 already prints, naming both accepted forms.

An explicit `--stack` always wins and is never second-guessed. The search is **one level and never
further** — the same discipline as Decision 2's one-level scan, for the same reason: a rule that
walks an arbitrary distance behaves differently depending on where the tree happens to sit. `cargo`
walks to the filesystem root; this deliberately does not, and the cost is that running from
`work/orders/web/` is refused rather than resolved, which the refusal message makes a two-second
fix.

Discovery runs the **whole** stack, not the application the shell happens to be inside. Narrowing
stays explicit (`--app-name`, Decision 3), because a stack silently missing its neighbours is
cross-application links answering 404 in exactly the runs that were started for convenience.

**`host` keeps `--stack` required.** It is run by operators and service units, where the working
directory is an accident of the supervisor and an implicit start is a hazard rather than a
convenience. The development loop guesses so the developer does not have to type; production does
not guess.

## The complete mapping

Every command, and what these decisions do to it. `+set` means the command joins a Decision 5 set
and gains its options.

| Command | Change |
| --- | --- |
| `serve` | **becomes `dev`**; `--app` → **`--stack`**, which is now the only way to name what runs (Decision 1), discovered one level up when omitted (Decision 9); gains `--app-name` for narrowing (Decision 3); `+config` (already had `--env`) |
| `host` | `--install-root` → **`--stack`**, always explicit — production does not guess (Decision 9); **`--mode` deleted** with independent hosting (`stack-architecture.md` Decision 12); keeps `--port`, `--http2`, `--trusted-proxies`; gains `--app-name` (Decision 3); `+config` |
| `mcp` | gains **`--stack`** (discovered one level up when omitted, Decision 9) and `--app-name` — `stack-architecture.md` Decision 19 makes the development-tool MCP span the stack, and Decision 3 is how it narrows; `--read-only` becomes a property of the server, not of an application; `+config` |
| `new` | `--dir` → **`--stack`**, not `--app` — see Decision 8; it names the *parent*, which is by definition a directory holding applications. The "Next steps" it prints are rewritten with it |
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
| 1 | The two resolvers (Decisions 1–3): one application, or the applications in a stack, with the refusals under test |
| 2 | `--install-root` → `--stack` on `host`; `--stack` added to `mcp` per Decision 19 |
| 2a | **The amendments of 2026-08-16:** `--app` removed from `dev` / `host` / `mcp`; the `APPLICATION` shape stops defaulting to the origin root, which becomes a declared address like any other; `--app-name` for narrowing (Decision 3); `id` deleted as a synonym for an application's `name` across `InstalledApp`, `catalog.json` and the `appId` parameters (Decision 6) |
| 3 | `serve` → `dev`, over `--stack`, through the gateway; `--port` as the front door, and `MultiAppHost` honouring a declared `server.port` instead of always calling `freePort()` (Decision 4a) |
| 3a | `--embedded-db` for a stack (Decision 4b): the coordinates supplied through the environment source, the declared query string carried over, the pool-level backstop and its one line |
| 4 | The three `@Mixin` sets (Decision 5) applied across every command, `--modules` membership settled per command, and the shape guard of Decision 7 |
| 5 | `new --dir` → `--stack` and its "Next steps" (Decision 8); `reference-cli.md` regenerated; `hosting.md` and `getting-started.md` rewritten to the new surface |

**The `--app-name` deletion is not in these slices.** It depends on the history-key change of
Decision 6, which fixes a defect that bites `serve` and every scaffolded application today and has
no reason to wait for a stack. It ships on its own, before or beside slice 1.

Slice 1 is first because slices 2 and 3 are both callers of it, and because it is where the
application-versus-stack refusals live. Slice 4 is last of the mechanical ones because it touches
the most files and conflicts with everything.

## Out of scope

- **Renaming `--jdbc-url`, `--username`, `--password`.** They are precise and widely copied into
  scripts. The problem was that the set was incomplete, not that the words were wrong.
- **Subcommand renames beyond `serve` → `dev`.** `scaffold`, `governance` and `admission` are
  arguable and none of them is confusing enough to pay for.
- **A configuration file for CLI defaults.** A real want, and a separate decision: it interacts with
  profiles, with secrets, and with what a repository should commit.
- **Id-based narrowing inside a stack.** Deferred with a trigger — Decision 3a.

## Open questions

1. **Whether `dev` implies `--embedded-db`.** A zero-configuration `tesseraql dev` is attractive and
   would make the first five minutes shorter. Against it: a developer with a real database gets an
   embedded PostgreSQL started for no reason, and "why is it not reading my data" is an expensive
   first question. Recommendation: do not imply it; make `getting-started.md` show the flag.
2. **What `dev --stack` does when the folder holds no application.** An error is obvious; whether it
   should offer to scaffold one is not.
3. **Studio's API console behind a gateway** — Decision 4a. It needs the base path and it needs to
   stop treating an internal port as a misconfiguration. Whether it should address the gateway
   instead of the runtime is the real question, and it is a Studio decision rather than a CLI one:
   `stack-architecture.md` slice 8 is where a stack-level Studio is designed, and this may belong
   there rather than being patched twice.
