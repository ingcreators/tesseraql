# WinGet distribution: the CLI installs with `winget install ingcreators.TesseraQL`, and every release updates it

> **Status: designed 2026-09-26, against main `a5331a99a` (0.20.0-SNAPSHOT), the day 0.19.0
> shipped.** The maintainer asked whether publishing the Windows CLI through WinGet or the
> Microsoft Store costs money. WinGet costs nothing, and the Store is free for an individual. The
> maintainer then asked which is easier for a user and how to proceed, and chose to release
> 0.19.0 first so that the first listed version carries the release's fixes. This resumes
> [Phase 38](roadmap.md#phase-38--cli-distribution-and-upgrade-delivery)'s Tier 2 for WinGet alone.
> Everything below was read in the code, in the 0.19.0 release asset, or in WinGet's own source
> and manifests (rows 1-9). The maintainer chose every recommendation but one: the token that
> submits updates is issued on the maintainer's own account rather than a machine account, and
> decision 6 records the limits that choice is kept within.
>
> There are two slices:
>
> **S1 (the first submission).** The three manifests below, for 0.19.0, are validated and
> installed on Windows, then submitted to `microsoft/winget-pkgs` from the maintainer's account.
> It has no framework change; the record gains its status line when the package is merged.
>
> **S2 (every release after).** `release.yml` submits each new version the way it bumps
> Homebrew and Scoop, and the documentation and the update hint name WinGet.

## What is true today

1. **Windows users have three ways in, none of them WinGet.** The Scoop bucket
   (`ingcreators/scoop-bucket`, bumped by `release.yml`), the jar distribution (JDK 25 on `PATH`),
   and the jpackage app image downloaded by hand ([getting-started.md](getting-started.md#install-the-cli)).
   Scoop must itself be installed first, and a user must know the bucket's URL.
2. **The Windows app image is one zip with one directory.** `tesseraql-0.19.0-windows-x86_64.zip`
   (90,264,821 bytes, SHA-256 `8c06dab6…`) holds `tesseraql/tesseraql.exe` with `tesseraql/app/`
   and `tesseraql/runtime/` beside it, 301 entries. The launcher is a PE32+ x64 image with the
   console subsystem (`--win-console`, `jpackage.yml`), and it finds `app\` and `runtime\` next to
   itself, so it works only where it sits, beside them.
3. **`release.yml`'s `bump-package-managers` waits for that zip.** jpackage attaches it to the
   release asynchronously; the job waits through `.github/scripts/await-release-assets.sh`, reads
   its digest, and pushes the Scoop manifest with a token minted from the org's GitHub App. That
   App is installed on `homebrew-tap` and `scoop-bucket` only.
4. **The update hint names two channels.** `UpdateNotifier` prints `brew upgrade tesseraql /
   scoop update tesseraql, or download: …`, and [upgrading.md](upgrading.md#upgrading-the-cli)
   lists the same two.
5. **A WinGet package is three YAML files in `microsoft/winget-pkgs`**, under
   `manifests/i/ingcreators/TesseraQL/<version>/`: a version manifest, a default-locale manifest
   and an installer manifest. Every submission is a pull request that runs the automated
   validation (the installer is downloaded, scanned and installed in a sandbox); a new package is
   also reviewed by a moderator. Nothing is paid, and no account beyond GitHub is needed.
6. **A zip of portable files installs by extraction, and the command reaches the user one of two
   ways** (read in winget-cli's `PortableInstaller.cpp`). By default WinGet creates a symbolic
   link to the executable in its links directory, which is on `PATH`. With
   `ArchiveBinariesDependOnPath: true` it skips the link and adds the executable's own directory
   to `PATH` instead, "for portables dependent on binaries that require the install directory".
   The key exists from manifest schema 1.9.0; the current schema is 1.12.0, and a package already
   merged with it at the manifest root (`Google.Protobuf` 36.0) is the shape used below. A `PATH`
   change reaches only a terminal opened afterwards. On uninstall, the added directory can stay
   on `PATH` (winget-cli [#6160](https://github.com/microsoft/winget-cli/issues/6160), open,
   reported on 1.28).
7. **Three tools can submit an update, and each needs a classic personal access token with
   `public_repo`**: fine-grained tokens cannot open the pull request to `microsoft/winget-pkgs`.
   - **komac** 2.16.0 (Linux, macOS and Windows binaries, a published `SHA256SUMS`):
     `komac update <id> --version <v> --urls <url> --submit`, with `--dry-run` and `--output`.
     It needs the token even for a dry run: without one it reaches for the OS keyring and fails in
     a container. `komac analyze` reads our zip as `Architecture: neutral` and names no nested
     executable, so the first manifest must state both. It submits from the fork owned by
     `KOMAC_FORK_OWNER`, or by the token's user without it (`get_username`), and creates the
     branch at `microsoft/winget-pkgs`' head commit, so the fork need not be synced first.
   - **wingetcreate** is Microsoft's, and runs on Windows only.
   - **WinGet Releaser** (an Action over komac) also needs the `workflow` scope, and starts on a
     published release, an event our release never emits: `release.yml` creates the release with
     `GITHUB_TOKEN`, and events that token causes start no workflow.
8. **A classic token cannot be narrowed to one repository.** `public_repo` writes to every
   public repository its owner can write to. The maintainer's account is this repository's
   administrator, which the `main-protection` ruleset lets past it ([release.md](release.md)).
9. **The Microsoft Store would take an MSIX (signed by the Store) or a signed MSI/EXE.** jpackage
   makes neither from this build, and the Store lists desktop applications; a command-line tool is
   reached from a terminal.

## The decisions

### 1 — What is published: the CLI's Windows app image, x64

The zip of row 2, unchanged, under the identifier `ingcreators.TesseraQL`. It carries its own
Java runtime, so a WinGet user needs no JDK, as a Scoop user needs none. The host's Windows zip
(`tesseraql-host-…`, a service wrapper for a server) is not a developer's tool and is not
published (decision 7). Only x64 is built; Windows on Arm runs it under emulation.

### 2 — How it installs: extracted, and its directory on `PATH`

`ArchiveBinariesDependOnPath: true`, because the launcher runs only beside `app\` and `runtime\`
(row 2): a link in WinGet's links directory would start an executable that cannot find its
application. WinGet adds `…\WinGet\Packages\ingcreators.TesseraQL_…\tesseraql\` to the user's
`PATH`; the directory's name carries no version, so an upgrade keeps the same entry. No
`PortableCommandAlias`: WinGet reads it only for the link it will not make, and the command is
the executable's own name, `tesseraql`. The default user scope needs no administrator; `--scope
machine` works as for any portable package.

### 3 — The first manifests, for 0.19.0

`manifests/i/ingcreators/TesseraQL/0.19.0/ingcreators.TesseraQL.yaml`:

```yaml
# yaml-language-server: $schema=https://aka.ms/winget-manifest.version.1.12.0.schema.json

PackageIdentifier: ingcreators.TesseraQL
PackageVersion: 0.19.0
DefaultLocale: en-US
ManifestType: version
ManifestVersion: 1.12.0
```

`ingcreators.TesseraQL.locale.en-US.yaml`:

```yaml
# yaml-language-server: $schema=https://aka.ms/winget-manifest.defaultLocale.1.12.0.schema.json

PackageIdentifier: ingcreators.TesseraQL
PackageVersion: 0.19.0
PackageLocale: en-US
Publisher: ingcreators
PublisherUrl: https://ingcreators.com
PublisherSupportUrl: https://github.com/ingcreators/tesseraql/issues
PackageName: TesseraQL
PackageUrl: https://ingcreators.com/tesseraql
License: Apache-2.0
LicenseUrl: https://github.com/ingcreators/tesseraql/blob/main/LICENSE
ShortDescription: CLI for TesseraQL, the SQL-first hypermedia application framework (bundled Java runtime)
Moniker: tesseraql
Tags:
- cli
- framework
- htmx
- sql
ReleaseNotesUrl: https://github.com/ingcreators/tesseraql/releases/tag/v0.19.0
ManifestType: defaultLocale
ManifestVersion: 1.12.0
```

`ingcreators.TesseraQL.installer.yaml`:

```yaml
# yaml-language-server: $schema=https://aka.ms/winget-manifest.installer.1.12.0.schema.json

PackageIdentifier: ingcreators.TesseraQL
PackageVersion: 0.19.0
InstallerType: zip
NestedInstallerType: portable
NestedInstallerFiles:
- RelativeFilePath: tesseraql\tesseraql.exe
ArchiveBinariesDependOnPath: true
Commands:
- tesseraql
ReleaseDate: 2026-09-26
Installers:
- Architecture: x64
  InstallerUrl: https://github.com/ingcreators/tesseraql/releases/download/v0.19.0/tesseraql-0.19.0-windows-x86_64.zip
  InstallerSha256: 8C06DAB6AB573553CD945A0673250E1E2A00BD585E9EC67AE00E1CF74D779E6C
ManifestType: installer
ManifestVersion: 1.12.0
```

The description is the Scoop manifest's, so the two channels say the same thing. The moniker
lets `winget install tesseraql` find the package too.

### 4 — Every later version is komac's update, in its own job

A `bump-winget` job in `release.yml`, beside `bump-package-managers`, on `ubuntu-latest`:

- It checks out the tag (it runs a repository script) and waits for the Windows zip through
  `await-release-assets.sh`, as the Scoop bump does. It carries no polling loop of its own.
- It fetches komac by version and verifies it against the release's `SHA256SUMS`, pinned in the
  workflow, as the Maven distribution is fetched against its checksum.
- It runs `komac update ingcreators.TesseraQL --version <v> --urls <zip url> --release-notes-url
  <release url> --submit` with `KOMAC_FORK_OWNER=ingcreators` and the token from the
  environment, never on the command line, then `komac cleanup --only-merged` so the fork keeps
  no branch whose pull request has merged.
  komac carries every other field over from the version before; S2 proves it does for the
  nested file and `ArchiveBinariesDependOnPath` before the job is wired.
- It runs in the `winget` environment, the only place `WINGET_TOKEN` lives (decision 6). Without
  the secret it says so and skips, as the Homebrew and Scoop bump does without the App's
  credentials.

It is a job of its own, not a step of `bump-package-managers`, so an expired token turns one job
red and leaves Homebrew and Scoop bumped. The release itself is already published when it runs.

komac, not wingetcreate, because it runs on the Linux runner every other release job uses. Not
WinGet Releaser, because it would need the `workflow` scope and a release event that never
arrives (row 7).

### 5 — The first submission is made by hand

An update needs a version already in the repository (komac `update`, WinGet Releaser and
wingetcreate `update` all start from the version before), so the first is submitted by the
account holder, after two checks on a Windows machine:

```powershell
winget validate --manifest <dir>
winget settings --enable LocalManifestFiles     # once, as administrator
winget install --manifest <dir>
```

In a new terminal, `tesseraql --version` answers `TesseraQL 0.19.0`, and `winget uninstall
ingcreators.TesseraQL` removes it again. Then `komac submit <dir>` with the token of decision 6 in `GITHUB_TOKEN` and
`KOMAC_FORK_OWNER=ingcreators`, or a pull request made by hand from `ingcreators/winget-pkgs`. A moderator reviews a new package; if a check asks the account to
agree to a contributor licence agreement, the account holder agrees once.

### 6 — The token is the maintainer's, and only a release tag can read it

The maintainer's choice, between two: the token is issued on the maintainer's own account, one
account fewer to keep. The fork it submits from is the organisation's,
[`ingcreators/winget-pkgs`](https://github.com/ingcreators/winget-pkgs) (forked 2026-09-26), so
every pull request comes from `ingcreators:<branch>`; komac is pointed at it with
`KOMAC_FORK_OWNER=ingcreators` (row 7), and the token writes to it through the maintainer's
membership.

A classic token reaches every public repository its owner can write to (row 8), so a leaked
`WINGET_TOKEN` could push to this repository past its branch protection. The recommendation was
a machine account that holds only its fork, whose worst case is a pull request under its own
name; decision 8 keeps it, with its trigger. The token is kept within these limits instead:

- **Scope and life:** `public_repo` alone, and an expiry, with a reminder to renew it before it
  lapses. An expired token turns `bump-winget` red, nothing else (decision 4).
- **Who can read it:** the secret lives in a GitHub environment, `winget`, whose deployment rule
  admits only `v*` tags, and `bump-winget` is the one job that names the environment. A branch's
  workflow, a pull request's, or another release job cannot read it; the repository-level
  secrets hold no copy. Re-running a tag's failed `bump-winget` keeps the tag's ref, so the rule
  admits it; `release.yml` dispatched from `main` does not, and the job skips there.
- **What receives it:** komac alone, the version pinned and verified against its published
  checksum before it runs (decision 4), and through the environment, never on the command line.

### 7 — What the user is told

- **[getting-started.md](getting-started.md#install-the-cli)** leads Windows with
  `winget install ingcreators.TesseraQL` and keeps Scoop beside it. A new terminal is needed
  before `tesseraql` resolves.
- **[upgrading.md](upgrading.md#upgrading-the-cli)** gains `winget upgrade ingcreators.TesseraQL`.
  A running `tesseraql` (a `dev` session) holds its files open, so stop it first. After
  `winget uninstall`, the `PATH` entry may stay behind (row 6), and the page says where to remove
  it.
- **The update hint** names `winget upgrade ingcreators.TesseraQL` beside the other two.
- **[release.md](release.md)**'s account of what a tag sets in motion names `bump-winget`, and
  what it waits for.
- **[roadmap.md](roadmap.md)**'s Phase 38 Tier 2 records WinGet as shipped.

### 8 — What this design refuses, each with its trigger

| Refused | Why | Trigger |
| --- | --- | --- |
| The Microsoft Store | It takes an MSIX or a signed installer (row 9), jpackage makes neither, and a terminal tool gains little from a Store page | A desktop application, or users asking for it |
| Code signing | WinGet does not require it, and the Scoop channel has shipped unsigned since it opened | WinGet's validation or Defender flagging the launcher, or users reporting SmartScreen; SignPath Foundation (free for open source) is the first candidate |
| The host's Windows zip on WinGet | A service wrapper for a server, installed by an operator, not a developer's tool | An operator asking for it |
| An Arm64 Windows build | Nothing builds one; x64 runs under emulation | A Windows on Arm user reporting that it does not |
| A hint that names only the channel that installed the CLI | Three commands on one line are still one line | Users reporting the hint as noise |
| WinGet Releaser | The `workflow` scope, and a release event our release never emits (row 7) | The release created by a token whose events start workflows |
| A machine account for the token | The maintainer's choice: one account fewer to keep, within decision 6's limits | A second maintainer, or the maintainer's account gaining reach the token would carry |

## What this changes

Nothing that exists changes behaviour. Windows users gain a channel; Scoop, the archives and
their bumps stay as they are. A release gains one job, which skips without its secret.

## The slices

### S1 — the first submission (outside this repository)

Decisions 1-3, 5 and 6.

- `ingcreators/winget-pkgs` exists (done 2026-09-26), and the maintainer holds a classic token
  with `public_repo` alone and an expiry. If the organisation refuses classic tokens, its
  personal-access-token setting admits them.
- The three manifests of decision 3 pass `winget validate`, install from the manifest on
  Windows, answer `tesseraql --version` with `TesseraQL 0.19.0` in a new terminal, and uninstall.
- The pull request to `microsoft/winget-pkgs` is merged, and `winget install
  ingcreators.TesseraQL` installs 0.19.0 on a machine that never saw the manifests.
- **Docs:** this record's status line.

### S2 — every release after (S)

Decisions 4 and 7.

- **Before wiring the job**, the pinned komac, run with `--dry-run --output` and the account's
  token against the published 0.19.0, writes a manifest with `Architecture: x64`, the nested
  `tesseraql\tesseraql.exe` and `ArchiveBinariesDependOnPath: true` (row 7 says komac cannot infer
  the first two from the zip).
- `WorkflowLedgerTest` passes with the new job: pinned actions, a timeout, a checkout before the
  script, the checksum.
- The maintainer creates the `winget` environment (deployment rule: `v*` tags) and sets
  `WINGET_TOKEN` in it, not at the repository level. The job's first real run is the 0.20.0
  tag.
- **Docs:** decision 7's pages, the update hint and its test, and the CHANGELOG under Added.
