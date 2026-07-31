# procurement-app

The suite-scale gallery application (docs/procurement-demo.md): an end-to-end
buyer/supplier procurement flow built up in slices. **Slices 1–4** are in place —
purchase requisitions with decision-routed approval, the RFQ leg, the supplier portal,
and the comparison-to-order step:

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
  riding the transactional outbox (`notify:` + the `procurement-mail` channel);
- **the supplier portal** (slice 3): suppliers log into the same app with a `partner`
  claim and reach **only their own partner's rows** (`scope/quotes_scope.yml`) — a
  competitor's quote is outside their row reach, not merely hidden. Starting a quote
  copies the requisition's lines in one two-step transaction (deterministic id, so
  restarts are no-ops); pricing maintains the counters the quote workflow's submit
  guard reads — an **app-mode status-column workflow** beside the managed ones, both
  modes in one app;
- **comparison → purchase approval → order** (slice 4): the comparison ranks submitted
  quotes with each one's distance from the lowest; creating an order **computes and
  stamps the selection facts** (total, lowest-or-not, % above lowest) — never
  client-asserted. Picking a non-lowest quote demands a written reason
  (`rules/orders.yml`, 422 before anything writes), and the **`orderApproval`
  decision** routes the submit: lowest or within 3% issues without a human in the
  loop, anything above waits for the procurement head — deviation is allowed,
  recorded, and approved, never silently blocked.

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

The supplier portal (role `SUPPLIER` + a `partner` claim):

```
GET  /api/supplier/rfqs               # issued RFQs this partner is invited to
POST /api/supplier/quotes             # start a quote (copies the lines; idempotent)
POST /api/supplier/quotes/{id}/lines  # price a line (keeps the submit-guard counter)
POST /api/supplier/quotes/{id}/submit # guarded: every line priced
```

Comparison and ordering (procurement):

```
GET  /api/rfqs/{id}/comparison        # submitted quotes ranked, distance from lowest
POST /api/orders                      # non-lowest pick needs a reason (422 otherwise)
POST /api/orders/{id}/submit          # orderApproval decision routes the lane
POST /api/orders/{id}/issue           # auto lane: no human in the loop
POST /api/orders/{id}/approve_issue   # review lane: the head's task
```

## Test it

```bash
tesseraql test --app .
```
