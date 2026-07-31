# procurement-app

The suite-scale gallery application (docs/procurement-demo.md): an end-to-end
buyer/supplier procurement flow built up in slices. **Slices 1–2** are in place —
purchase requisitions with decision-routed approval, and the RFQ leg:

- a `kind: workflow` requisition document whose `submit` evaluates the shared
  **`approvalRoute` decision** (two inputs: amount × category) and stamps the lane;
  the manager lane approves in one step, the two-stage lane passes through the
  procurement head — every lane has exactly one path out;
- **row-level data scoping** (`scope/requisitions_scope.yml`): procurement sees every
  department, requesters and managers see their own (`departments` claim),
  deny-by-default for everyone else; lines ride the header's scope through the join;
- **field authorization**: the buyer-internal cost estimate is absent from the JSON API
  unless the caller clears `req.cost`; the HTML list simply never selects it;
- **the RFQ leg** (slice 2): a second `kind: workflow` document — procurement drafts an
  RFQ **from an approved requisition only** (a shared rule reading the managed workflow
  state, `rules/rfqs.yml`), invites suppliers, the procurement head approves the issue,
  and the issued state opens a follow-up task with an **engine deadline** — a week
  unattended and the sweeper reassigns it to the head, with assignment/escalation mail
  riding the transactional outbox (`notify:` + the `procurement-mail` channel).

Part of the template gallery; held to the marketplace admission profile
(`tesseraql admission --app .`).

## Run it

```bash
tesseraql serve --app . --embedded-db     # embedded PostgreSQL, auto-seeded
```

Sign a dev bearer token (roles `REQUESTER` + claim `departments: [engineering]`, or
`PROCUREMENT`) and walk the flow:

```
GET  /api/requisitions                # scoped list; internal_estimate needs req.cost
POST /api/requisitions                # create (draft)
POST /api/requisitions/{id}/submit    # decision stamps the lane, task goes to the dept manager
POST /api/requisitions/{id}/advance   # two-stage lane: on to the procurement head
POST /api/requisitions/{id}/approve_final
```

The RFQ leg (roles `PROCUREMENT` / `PROCUREMENT_HEAD`):

```
POST /api/rfqs                        # from an approved requisition (422 otherwise)
POST /api/rfqs/{id}/suppliers         # invite a partner (idempotent)
POST /api/rfqs/{id}/submit            # approval task to the procurement head
POST /api/rfqs/{id}/issue             # follow-up task + 168h reminder deadline
GET  /api/partners
```

## Test it

```bash
tesseraql test --app .
```
