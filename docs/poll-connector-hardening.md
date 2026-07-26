# Poll connector hardening

> **Status: slices 1–3, 5 and 6 shipped; 4 and 7 designed.** The 2026-07-25 contract-deviation sweep compared
> the three poll sources (`local`, `sftp`, `ftps`) against each other and found that `ftps` is
> not, as [connectors.md](connectors.md) states, "the identical recipe and runtime path … only
> the endpoint scheme differs" — it transfers file content **unencrypted**, validates **no
> server certificate**, corrupts binary payloads, and cannot connect from behind NAT. Three
> findings were confirmed against `camel-ftp-4.18.0` bytecode; the branch has **no test
> coverage at all**, which is why they survived ~13 months. This document defines the credential
> and transport model the three sources should share, and the lint that makes an unverified
> remote source unrepresentable.
>
> **Slices 1–2 are shipped:** the ftps endpoint now negotiates `PBSZ 0` + `PROT P`, transfers in
> binary, and connects in passive mode, and `PollImportFtpsIntegrationTest` exercises the branch
> against an in-process Apache FtpServer — asserting the server's own command trace, which is
> what actually distinguishes the settings (a lenient server round-trips text intact even in
> ASCII mode, so a payload-only assertion passes against the broken setting). Against the old
> code the trace reads `TYPE A` with no `PBSZ`/`PROT` at all.
>
> **Slice 3 (server identity) is shipped:** `tesseraql.connectors.poll.trustStore` pins the CA an
> FTPS server's certificate must chain to, hostname checking is on, and an ftps source without a
> trust store is refused at wiring time with `TQL-SEC-4085` (an **error**, not the warning its
> SFTP sibling raises — an unverified CA has no first-use posture to preserve). The integration
> test carries a negative case: a server presenting a certificate the client does not trust
> ingests nothing, which is what makes the control self-verifying. Slices 4–7 remain.

The failure class: a recipe family whose members are documented as interchangeable, where the
security posture silently differs per member. An author hardens their SFTP job after the
`TQL-SEC-4084` nudge, adds a second partner over FTPS believing it is the same path, and ships an
unauthenticated, cleartext transfer that no lint mentions.

## What each source actually does today

| | `local` | `sftp` | `ftps` |
| --- | --- | --- | --- |
| Content encrypted in transit | n/a | yes | yes (was **no**) |
| Server identity verified | n/a | only with `knownHostsFile` | yes, required (was **never**) |
| Path / host allow-list enforced | yes, required (was **no**) | yes | yes |
| Path governance (root anchoring, traversal) | yes (was **none**) | n/a (remote) | n/a (remote) |
| Binary-safe | yes | yes | yes (was **ASCII mode**) |
| Works behind NAT | n/a | yes | yes (was **active mode**) |
| Credential kinds | n/a | password only | password only |
| Anonymous connection possible | n/a | **yes, unflagged** | **yes, unflagged** |
| Streams off-heap | yes | yes (was **no**) | yes (was **no**) |
| Test coverage | one IT | unit + IT | unit + IT (was **none**) |

## The confirmed findings

### FTPS transfers file content in the clear — FIXED

`PollingRouteBuilder:102-103` appends `disableSecureDataChannelDefaults=true`, which is the
inverse of a hardening flag. In `camel-ftp-4.18.0`, `FtpsOperations.connect` reads
`isDisableSecureDataChannelDefaults()` and **skips** the block that would set `execProt="P"` and
`execPbsz=0`; both stay null, so neither PBSZ nor PROT is ever sent. `FTPSClient.execPROT` is the
only site that installs `FTPSSocketFactory`, so `_openDataConnection_`'s `instanceof SSLSocket`
gate falls through to a plain socket. A repo-wide grep for `execProt|execPbsz|sslContextParameters`
returns exactly one hit: line 103 itself.

The control channel — including USER/PASS — is TLS-protected via AUTH TLS. File content and LIST
output are not. The introducing commit carries no rationale for the flag.

### FTPS validates no server certificate, and nothing can configure one — FIXED

With `sslContextParameters` and both `ftpClient*StoreParameters` null, `FtpsEndpoint.createFtpClient`
never calls `setTrustManager`, leaving commons-net's default
`TrustManagerUtils.getValidateServerCertificateTrustManager()` — a loop over
`X509Certificate.checkValidity()`, with no chain building and no trust anchor. `tlsEndpointChecking`
defaults false and `hostnameVerifier` is null, so hostname verification is absent too.
`FtpsComponent.useGlobalSslContextParameters` defaults false, so a registry bean would not apply —
and there is none.

`PollConnectors` parses `allowedHosts`, `knownHostsFile`, and `credentials`; `remoteUri` forwards
only `username` and `password`. **There is no YAML path to fix this.** Combined with the previous
finding: an on-path attacker presents any in-date self-signed certificate, harvests the
credentials, and reads every file in the clear.

### FTPS runs in ASCII mode and active mode — FIXED

`binary` and `passiveMode` are never set and both default false. `FtpOperations.connect` actively
calls `setFileType(ASCII_FILE_TYPE)` and skips `enterLocalPassiveMode()`. ASCII mode
line-ending-translates binary payloads in transit, so a `format: excel` import — a documented
import format — arrives corrupt. Active mode requires the server to open a connection back to the
runtime, which fails for any containerized or NAT'd deployment, i.e. effectively always in this
project's target shapes. The identical job on `source: sftp` works.

### SFTP host-key checking is off by default

`PollingRouteBuilder:115-121` emits `strictHostKeyChecking=no` when no `knownHostsFile` is
configured, and `TQL-SEC-4084` is a **warning**, so nothing gates a ship.

Two corrections the verification pass forced, worth recording so the fix is not
over-scoped: this does **not** override a safe Camel default (`strictHostKeyChecking` defaults to
`'no'` in the component itself), and it is orthogonal to `useUserKnownHostsFile`, which stays true
and still loads `~/.ssh/known_hosts`. The emitted option is a redundant restatement of Camel's
default. The risk is real; the framing "TesseraQL asserts an unsafe posture" is not.

## The unverified leads

The sweep raised these; no adversarial verifier examined them. They are listed here because the
model below has to answer them, not because they are established.

- **`source: local` has no path governance** — VERIFIED in full. The allow-list gate sits behind
  `poll.isRemote()`, so `local` never reaches one, and the local arm concatenates `path:`
  verbatim with no anchoring and no `normalize()`. A probe with `path: <tmp>/app/data/../../secret`
  polled a file out of the sibling directory *and moved it* into `<tmp>/secret/.done/` — read and
  write outside any nominal root. `autoCreate` does default true (confirmed in `file.json` and
  empirically), `ComponentPolicy.FRAMEWORK_FLOOR` always admits `file`, and no `allowedPaths`
  key exists anywhere. The asymmetry with `FileScopes` is exact: it resolves under a declared
  root, normalizes, and re-checks the prefix.
- **Endpoint option injection** — VERIFIED, with two corrections. `include`→`antInclude`,
  `move`, `moveFailed` and `username` are concatenated unescaped while `password` is wrapped in
  `RAW(...)`; Camel splits the query on `&` before binding, so the author gets arbitrary
  consumer-option control (a probe bound `noop=true` and `recursive()=true` onto the resolved
  `FileEndpoint`). **`path` is not injectable** — it precedes the `?`, and a `?` inside it
  produces a URI Camel refuses outright; its problem belongs to the path-governance lead above.
  And the payload originally proposed here does **not** re-import: `.done`/`.error` are
  dot-prefixed and `includeHiddenDirs`/`includeHiddenFiles` default false, so `recursive=true`
  cannot reach them, while `noop=true` makes Camel force `idempotent=true` with an in-memory
  repository. Unbounded re-import needs
  `**/*.csv&recursive=true&noop=true&idempotent=false&includeHiddenDirs=true&includeHiddenFiles=true`
  (probe: 4 deliveries in 6 cycles, nothing moved). The low-effort harm from the simple payload
  is real but different — `noop=true` alone stops the move, so the inbound directory never
  drains and every process restart re-imports everything still sitting in it.
- **`move`/`moveFailed` are Simple expressions, and that is the sharper edge.** It needs no `&`:
  `move: "${file:parent}/../../escaped/${file:onlyname}"` relocated the polled file outside the
  poll tree in a probe — an arbitrary-destination write of the file's contents from a plain YAML
  scalar, with no lint anywhere. Escaping `&` would not fix it; only rejecting path-ish and
  expression values will. `ComponentGuard` does not cover this either: `bean`/`language`/
  `script` are baseline-denied as *components*, while Simple's `${bean:…}` is a language.
- ~~**`moveFailed` is effectively dead for all three sources.**~~ **CONFIRMED and FIXED** (slice
  6). The import was asynchronous — spool, insert a row, submit, return — so the exchange
  completed and the file moved to `.done` before a single row of SQL ran, and `.error` could only
  ever collect the three synchronous failures. Confirmed by removing the fix: a file whose rows
  cannot bind lands in `.done` like any success.
- **A remote `path:` documented as absolute is home-relative.** `PollingRouteBuilder:128` strips a
  leading `/` that Camel's `GenericFileConfiguration.configure` already strips, so
  `path: /outbound/orders` — the form the docs show — resolves against the login home. Verified;
  the claim that absolute paths are *unexpressible* was refuted (`path: //outbound/orders` works,
  undocumented). The SFTP IT's `VirtualFileSystemFactory` roots the test user at `/`, which is why
  the difference is invisible in CI.
- **A remote source with no `credential:` connects anonymously**, unflagged by lint and by the
  runtime; `credential: ""` behaves differently again (lint silent, runtime `TQL-BATCH-5310`, job
  silently skipped at wiring).
- **No SFTP key auth, no FTPS client certificate.** `require("password")` makes a key-only
  credential fatal, so partners mandating key-based SFTP — common — cannot be integrated at all.
- ~~**Remote polls load whole files into heap.**~~ **CONFIRMED and FIXED** (slice 7). Both remote
  components default to loading the whole file before the route sees it, so the processor's "a
  large file never materializes in memory" held for `local` only.
- **`ComponentGuard` auto-allows the raw, unvalidated source string.** `source: ftp` — a plausible
  typo for `ftps`, and the cleartext sibling — exempts the `ftp` component from an `allowed:`
  narrowing even though the job itself is dead. `denied:` and the baseline still win, so this
  widens only the narrowing.
- **Lint parity gaps inside the poll block** — VERIFIED, one correction: `port` range
  unvalidated (negligible — it fails at connect with a clear error); `delay` unvalidated, and a
  bad value throws inside `wire()` where a `catch (RuntimeException)` **logs at ERROR** and drops
  the job — not silent, but the app boots healthy with a missing route, so an operator sees only
  "nothing is arriving"; `host`/`credential` on a `local` source parse and are discarded with no
  feedback; a blank or missing `path` does create a directory literally named `null` in the
  process working directory — though lint *does* error on it (`TQL-YAML-1005`), so the real gap
  there is that lint is not a boot gate.

## The model

Three principles, each answering a column of the table above.

**1. Transport security is not per-source-kind optional.** Every remote source verifies the server
and encrypts content, or the app does not boot. FTPS gains what SFTP has: `execProt=P` and
`execPbsz=0` unconditionally (delete `disableSecureDataChannelDefaults`), `binary=true`,
`passiveMode=true`, and a trust surface —

```yaml
tesseraql.connectors.poll:
  allowedHosts: [sftp.partner.example, ftps.partner.example]
  knownHostsFile: security/known_hosts        # sftp server identity (existing)
  trustStore:                                 # ftps server identity (new)
    file: security/partner-ca.p12
    password: ${FTPS_TRUSTSTORE_PASSWORD}
```

wired through `SSLContextParameters` with `tlsEndpointChecking` on. `knownHostsFile` and
`trustStore` become the same rule under two protocols, and both become **required** for their
source kind — `TQL-SEC-4084` graduates from warning to error and gains an FTPS sibling.

**2. Credentials describe an authentication method, not a username/password pair.** The credential
record grows `privateKeyFile` / `privateKeyPassphrase` (sftp) and `clientCertificate` (ftps) as
alternatives to `password`, with exactly one method required. `require("password")` stops being
the implicit contract, which also fixes the silent job-disabling when a key-only credential is
configured today. A remote source with no credential at all is a load error, not an anonymous
login.

**3. Every URI value is escaped or rejected.** `path`, `include`, `move`, `moveFailed`, and
`username` go through the same `RAW(...)`/encoding treatment `password` already gets, and the poll
block's values are validated at lint: port range, parseable delay, non-blank path, and — for
`local` — a path anchored under a declared root, the `FileScopes` rule the poll path never
adopted. Wrong-kind keys (`host` on a local source) become lint errors rather than silent noise.

The `//` escape hatch for absolute remote paths gets documented, or better: the leading-slash strip
at `:128` is deleted so the documented `path: /outbound/orders` means what it says and Camel's own
single strip does the rest. That is a behavior change for existing apps whose paths were silently
home-relative — the CHANGELOG entry names it, per rule 10.

## Slices

1. ~~**FTPS transport.**~~ **Shipped.** `disableSecureDataChannelDefaults` is gone; the endpoint
   sets `execPbsz=0`, `execProt=P`, `binary=true`, `passiveMode=true` explicitly, so the settings
   survive a change in the component's own defaults and state their intent at the call site.
2. ~~**The ftps test bed.**~~ **Shipped.** `PollImportFtpsIntegrationTest` runs an in-process
   Apache FtpServer with a keystore generated per run by the JDK's `keytool` (no key in the
   repository), and asserts the server's command trace: `PBSZ 0`, `PROT P`, `TYPE I`, `PASV`,
   plus the row landing in the database. A `PollingRouteBuilderTest` case pins the URI itself.
   One correction to the original plan worth recording: "a binary payload round-trips
   byte-identically" is **not** a usable assertion — this server returns multi-byte text intact
   in ASCII mode too, so that check passes against the broken setting. The trace is the evidence.
3. ~~**Server identity.**~~ **Shipped**, by a simpler route than designed. `SSLContextParameters`
   turned out to be unnecessary: the component exposes `ftpClient.trustStore.` as a multi-value
   URI prefix feeding `FtpsEndpoint.ftpClientTrustStoreParameters` (whose `file`/`password`/`type`
   keys build the trust manager — confirmed in the bytecode), and the generic `ftpClient.` prefix
   reaches `FTPSClient` bean properties, so `endpointCheckingEnabled=true` turns on hostname
   verification. Two option names in the original plan do not exist; checking the component
   metadata before writing them is what caught it.
   `TQL-SEC-4084` stayed a warning: SSH host keys have a legitimate trust-on-first-use posture
   that a CA bundle does not, so only the new FTPS check is an error.
4. ~~**Credential methods.**~~ **Shipped.** FTPS client certificates ride the credential rather
   than the connector config, because a certificate identifies *us* and the trust store proves
   who answered — they are opposite directions of the same handshake, and the trust store's
   shape would have suggested otherwise. A credential declaring `keyStoreFile:` presents it, and
   a password may accompany it: mutual TLS and a login are separate questions a server may ask
   together.
   **Key-based SFTP and exactly-one-method validation are shipped** (`TQL-SEC-4089`). Only a
   password was ever emitted, so an operator who wrote `privateKeyFile:` got a URI with no key
   and an error about a missing password — the failure named the wrong thing, which is the worst
   kind of message to debug against. Declaring both is refused rather than silently preferring
   one: which wins is exactly the question a deployment should never answer by experiment. A
   private key on an `ftps` source is refused too.
   **The load error for a remote source with no credential is shipped** (`TQL-SEC-4088`). It was
   accepted, and produced a URI with no username and no password: SFTP fails at connect with a
   message about the server, and FTPS may succeed as anonymous — a poll job quietly reading
   whatever an anonymous session can see. Neither outcome names the declaration as the incomplete
   part. Five existing tests were constructing remote sources without a credential, which is its
   own evidence for how easily the omission passes unnoticed.
5. ~~**URI value handling and poll lint parity.**~~ **Shipped**, after a verification pass that
   corrected the leads it was based on (see the marked-up leads above — `path` turned out not to
   be injectable, the proposed re-import payload does not re-import, and `move:` is the sharper
   edge). A local source now resolves under a declared
   `tesseraql.connectors.poll.allowedPaths` root, normalizes, and re-checks the prefix — the
   `FileScopes` rule, deny-by-default, with `TQL-SEC-4086` saying so at lint time
   (open question 3 answered: require a root, because defaulting quietly re-creates the
   "the user believes they configured a boundary" failure).
   `include` and `username` are `RAW(...)`-wrapped so an `&` cannot bind extra consumer options.
   `move`/`moveFailed` are **rejected** rather than escaped when they contain `${`, `..`, a
   leading `/`, `&` or `?`: Camel evaluates them as Simple expressions, so escaping would not
   have stopped `${file:parent}/../../escaped` from writing the polled file outside the tree.
   Lint gained the parity checks — unparseable `delay` (which otherwise drops the job at startup
   and leaves the app healthy with nothing arriving), out-of-range `port`, and `host:`/
   `credential:` on a local source.
   **Not** in this slice: the `ComponentGuard` source-string validation, which belongs with the
   component-intent inference rather than with URI handling.
6. ~~**`moveFailed` honesty.**~~ **Shipped**, by the first of the two options and uniformly for
   all three sources: the poll processor waits for the transfer to reach a terminal status and
   raises `TQL-LD-2849` when it did not complete, so Camel's own `move`/`moveFailed` finally
   mean what [connectors.md](connectors.md) says. The wait runs on the poll consumer's own
   thread, which is where it belongs — a poll job handles one file at a time by construction,
   the file's fate is the point of the cycle, and it gives the loop natural backpressure.
   Open question 1 answered: **not** by making local synchronous and leaving remote async. A
   per-source difference is the class of divergence this document exists to remove.
   The integration test drops a file whose rows cannot bind and asserts it reaches `.error`;
   without the wait it lands in `.done` like any success.
7. ~~**Off-heap remote polls.**~~ **Shipped**, with `localWorkDirectory` under the app work
   directory rather than `streamDownload`: the component writes the remote content straight to a
   file, so the spool that follows is a disk-to-disk copy and the consumer keeps its normal retry
   and move behavior — `streamDownload` would hand the route a live network stream, which the
   spool would then have to hold open. The processor's off-heap promise now holds for all three
   sources.

## Lint and tooling

- `TQL-SEC-4084` becomes an error and gains an FTPS analog for a missing trust store.
- New checks: remote source without a credential; credential declaring more than one auth method;
  wrong-kind key for the source; unparseable `delay`; out-of-range `port`; local `path` outside the
  declared roots.
- The component guard validates the source string against the known set before inferring intent, so
  a typo cannot widen an `allowed:` narrowing.
- The docs portal's connectors page gains the verified/unverified state per source, which is the
  fact an operator most wants at review time.

## Out of scope

- **Plain `ftp`.** It stays baseline-denied; the fix for a cleartext requirement is FTPS, not a
  supported cleartext source.
- **Per-job transport overrides.** Trust configuration is app-level, like `allowedHosts`; a job
  that needs different trust is talking to a different partner and should say so in the connector
  config.
- **Retrying the poll on transport failure beyond Camel's own consumer behavior.**

## Open questions

1. Should `moveFailed` become truthful by making the poll import synchronous for local sources
   (where the read is already lazy) and keeping it async for remote? Leaning no — a per-source
   difference is exactly the class of divergence this document exists to remove. Preferred: the
   transfer service moves the file when the import resolves, for all three sources, which finally
   makes `.done`/`.error` mean what the docs say.
2. Does deleting the leading-slash strip need a migration nudge, given apps may have been written
   against the home-relative behavior? Leaning: a lint warning for one release naming both readings,
   since the failure is silent in both directions (an app polling an empty directory forever looks
   healthy).
3. Should `local` sources require a declared root, or default to the app work dir? Leaning require —
   defaulting quietly re-creates the "the user believes they configured a boundary" failure this
   sweep is about.
