# Poll connector hardening

> **Status: slices 1–2 shipped, the rest designed.** The 2026-07-25 contract-deviation sweep compared
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
> code the trace reads `TYPE A` with no `PBSZ`/`PROT` at all. **Server identity is still
> unverified — slice 3 remains the security-critical one.**

The failure class: a recipe family whose members are documented as interchangeable, where the
security posture silently differs per member. An author hardens their SFTP job after the
`TQL-SEC-4084` nudge, adds a second partner over FTPS believing it is the same path, and ships an
unauthenticated, cleartext transfer that no lint mentions.

## What each source actually does today

| | `local` | `sftp` | `ftps` |
| --- | --- | --- | --- |
| Content encrypted in transit | n/a | yes | yes (was **no**) |
| Server identity verified | n/a | only with `knownHostsFile` | **never, unconfigurable** |
| Host allow-list enforced | **no** | yes | yes |
| Path governance (root anchoring, traversal) | **none** | n/a (remote) | n/a (remote) |
| Binary-safe | yes | yes | yes (was **ASCII mode**) |
| Works behind NAT | n/a | yes | yes (was **active mode**) |
| Credential kinds | n/a | password only | password only |
| Anonymous connection possible | n/a | **yes, unflagged** | **yes, unflagged** |
| Streams off-heap | yes | **no** | **no** |
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

### FTPS validates no server certificate, and nothing can configure one

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

- **`source: local` has no path governance.** The host allow-list gate applies to remote sources
  only; a local `path:` is concatenated verbatim with no root anchoring and no traversal check,
  unlike `FileScopes`, which anchors to a declared root and re-checks the normalized prefix. With
  camel-file's `autoCreate=true` default, a local poll job is a read-and-move filesystem primitive
  anywhere the process user can reach. No `allowedPaths` key exists.
- **Endpoint option injection.** `path`, `include`→`antInclude`, `move`, `moveFailed`, and
  `username` are concatenated unescaped while the adjacent `password` is wrapped in `RAW(...)` —
  as is a user-supplied cron in `SchedulingRouteBuilder`. An `include:` carrying
  `&recursive=true&noop=true` would make the consumer descend into `.done` and stop moving files:
  unbounded re-import. The actor is the manifest author, not an HTTP caller, which caps severity
  but not the surprise. Camel also evaluates `move`/`moveFailed` as Simple expressions.
- **`moveFailed` is effectively dead for all three sources.** The import is asynchronous — the
  transfer service spools, inserts a row, submits, and returns — so the exchange completes and the
  file moves to `.done` before a single row of SQL runs. Only three synchronous failures can route
  to `.error`. [connectors.md](connectors.md) documents the opposite, and an operator reconciling
  by directory concludes a failed file was ingested.
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
- **Remote polls load whole files into heap.** `streamDownload` and `localWorkDirectory` are unset,
  so the body is materialized before the route runs, contradicting `PollImportProcessor`'s "a large
  file never materializes in memory" and costing a second copy when the service spools it.
- **`ComponentGuard` auto-allows the raw, unvalidated source string.** `source: ftp` — a plausible
  typo for `ftps`, and the cleartext sibling — exempts the `ftp` component from an `allowed:`
  narrowing even though the job itself is dead. `denied:` and the baseline still win, so this
  widens only the narrowing.
- **Lint parity gaps inside the poll block:** `port` range unvalidated, `delay` unvalidated (a bad
  value silently disables the job), `host`/`credential` on a `local` source silently ignored, blank
  `path` on local creating a directory literally named `null`.

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
3. **Server identity.** `trustStore` config, `SSLContextParameters` wiring, `tlsEndpointChecking`;
   `TQL-SEC-4084` to error plus its FTPS sibling.
4. **Credential methods.** Key-based SFTP, FTPS client certificates, exactly-one-method validation,
   and a load error for a remote source with no credential.
5. **URI value handling and poll lint parity**, including the local path root anchoring and the
   `ComponentGuard` source-string validation.
6. **`moveFailed` honesty.** Either the file moves after the import resolves (the transfer service
   signals the consumer), or the documentation stops promising it and the operations console
   becomes the reconciliation surface. See the open question.
7. **Off-heap remote polls.** `localWorkDirectory` under the app work dir, or `streamDownload`, so
   the processor's off-heap promise holds for every source.

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
