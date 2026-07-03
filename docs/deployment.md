# Deployment

The reference deployment is a small VPS (e.g. Lightsail) running Docker containers deployed by
Kamal 2, fronted by Cloudflare (DNS, CDN, WAF) through a Cloudflare Tunnel, with a managed
PostgreSQL database. `deploy/Dockerfile` and `deploy/kamal/deploy.yml` are the templates.

```
users → Cloudflare (DNS / CDN / WAF / Access)
           │ tunnel (outbound-only; no open HTTP ports on the host)
           ▼
host: cloudflared → kamal-proxy → tesseraql runtime (:8080)
                                     └ volume: /app/work
managed PostgreSQL (sessions, jobs, outbox, file transfers all multi-node safe)
```

- `GET /_tesseraql/health/live` is the unauthenticated liveness endpoint (the process answers;
it never touches a dependency), and `GET /_tesseraql/health/ready` — also what the bare
`/_tesseraql/health` serves — is the readiness roll-up: it probes every configured datasource
live and answers `503 {"status":"DOWN"}` when one fails, `WARN` on active alerts, `UP`
otherwise (status word only, roadmap Phase 45). Point container health checks at
`/health/live` and load-balancer/proxy checks at `/health/ready`; the
  detailed health/metrics stay behind the authorized ops API.
- Put a Cloudflare Access policy on `/_tesseraql/*` so the system consoles sit behind both the
  Cloudflare login and the app's own authentication.
- `tesseraql.sessions.store: jdbc` keeps logins across container replacement.
- `/assets/**` is CDN-cacheable (ETag/Cache-Control are set); vendor assets use version-less
  URLs, so purge the Cloudflare cache when upgrading browser libraries.

## Shipping apps

**A. Baked image (default).** The app home is COPYed into the image; deploying the app is
`kamal deploy`. The running container maps one-to-one to a git commit, CI gates
(`lint`, `test`, `governance`, `release-evidence`) run before the build, and rollback is the
previous image.

**B. Local `.tqlapp`.** One runtime hosts several apps mounted from packages:
`tesseraql.apps.<name>.package` + `sha256`. App updates replace the file and restart; the
runtime refuses a package whose hash does not match.

**C. Fetched `.tqlapp` (multi-server).** The configuration names a URL and pins the hash; every
node fetches and verifies the same bytes at boot:

```yaml
tesseraql:
  apps:
    orders:
      url: https://artifacts.example.com/orders-1.2.0.tqlapp
      sha256: 4f2a...   # required - remote content is never mounted unpinned
```

Downloads land under `work/downloads` and are reused across restarts while the hash matches,
so nodes reboot without the artifact store being reachable. Rolling out a new version is a
config change (new url + sha) plus a rolling restart; pointing one host at the new hash first
is a per-host canary.

## Bootstrap and migrations

1. `mvn tesseraql:migrate -Dtesseraql.appHome=. -Dtesseraql.jdbcUrl=...` per datasource
   (`-Dtesseraql.datasource=<name>` for named connections), or rely on the mount-time
   migrations - both converge on the same per-app Flyway history.
2. `mvn tesseraql:identity-schema -Dtesseraql.jdbcUrl=... -Dtesseraql.adminLogin=admin
   -Dtesseraql.adminPasswordFile=... -Dtesseraql.adminRoles=ADMIN
   -Dtesseraql.adminPermissions=ops.app.*` seeds the first administrator. There are no default
   credentials; the role names must match the app's `tesseraql.security.policies`.
   `ops.app.<name>` permissions scope what an operator sees in the ops console and the
   `/_tesseraql/ops` API: batch jobs, executions, and traces are attributed to their owning app
   and hidden outside the caller's grants (deny by default); `ops.app.*` grants everything.
3. `kamal setup` / `kamal deploy`.

Kamal swaps containers with old and new briefly overlapping, so migrations must stay
expand/contract (backward compatible) - the same discipline the canary flow already requires.

## Multi-server notes

- Sessions, scheduled-job claims, outbox dispatch and file transfers are app- and node-safe on
  a shared database; adding a host is a `servers:` entry.
- Generated export files live on the node that produced them: keep session affinity at
  Cloudflare, or plug a shared TempStore implementation behind the SPI.
- Framework and app migrations take Flyway's lock, so concurrent node startups serialize.
