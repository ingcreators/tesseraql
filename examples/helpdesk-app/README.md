# helpdesk-app

A TesseraQL starter: a **helpdesk queue** where the ticket's own `status` column drives an
app-mode workflow (open → triaged → resolved → closed, with reopen) and creating a ticket
fires an assignment mail through the transactional outbox. Declarative views only. Part of
the template gallery (roadmap Phase 47); held to the marketplace admission profile
(`tesseraql admission --app .`).

## Run it

```bash
tesseraql serve --app . --embedded-db     # embedded PostgreSQL, auto-seeded queue
```

- `/tickets` — the queue (search, sort, pagination); `/tickets/new` opens one;
  `/tickets/T-2001` — the detail view.
- `POST /api/tickets/T-2001/triage` → `/resolve` → `/close` (or `/reopen`) — the synthesized
  workflow endpoints (bearer + `help.agent`); the state lives in `tickets.status`
  (app mode — contrast with purchase-request-app's managed mode).
- The assignment notice (`notify:` on create) rides the outbox; point `MAIL_HOST` at Mailpit
  to see it, or just read `tests/helpdesk-test.yml` — the suite asserts it without SMTP.

## Layout

See [docs/app-layout.md](../../docs/app-layout.md).
