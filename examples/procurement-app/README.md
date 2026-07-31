# procurement-app

The suite-scale gallery application (docs/procurement-demo.md): an end-to-end
buyer/supplier procurement flow built up in slices. **Slices 1–6** are in place —
purchase requisitions with decision-routed approval, the RFQ leg, the supplier portal,
the comparison-to-order step, delivery-date negotiation, and shipment-to-receipt:

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
  recorded, and approved, never silently blocked;
- **delivery-date negotiation** (slice 5): the supplier proposes a new date; the slip
  is computed against the ordered promise (never client-asserted) and judged by the
  **table-backed `deliveryAutoAccept` decision** — the tolerance is business data in
  `delivery_tolerances`, maintained at runtime (`/api/tolerances`), and the very next
  proposal is judged by the new rows, no deploy involved. Within tolerance the
  proposal confirms with no human in the loop; outside it a review task opens for
  whoever placed the order, who accepts or declines back to `issued`;
- **shipment to receipt** (slice 6): the supplier registers one shipment per confirmed
  order (the split-shipment fence is a `unique` constraint), the `ship` transition's
  WHERE demands the registered row — the guard lives in set-based SQL — and the
  **requester who started the chain closes it** with `receive`, stamping the shipment
  in the same transaction as the state advance. A `query-export` CSV covers the
  shipped lines, and the `/dashboard` reads the managed workflow state as rows.

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

Delivery-date negotiation:

```
POST /api/orders/{id}/confirm         # supplier: as ordered
POST /api/orders/{id}/date-change     # supplier: propose (slip computed server-side)
POST /api/orders/{id}/propose_accept  # slip within tolerance: auto-confirm
POST /api/orders/{id}/propose_review  # outside: review task for the buyer
POST /api/orders/{id}/accept_date | decline_date
GET/POST /api/tolerances              # the runtime knob the decision reads
```

Shipment and receipt:

```
POST /api/orders/{id}/shipment        # supplier registers (confirmed orders only)
POST /api/orders/{id}/ship            # fails without the registered shipment
POST /api/orders/{id}/receive         # the requester closes the chain
GET  /api/shipments/export            # CSV download
GET  /dashboard                       # the chain at a glance
```

## The tour — one requisition, three logins

The demo script (docs/procurement-demo.md): walk one purchase requisition from creation
to goods receipt across the three personas. Sign three dev bearer tokens (HS256 over the
dev secret) — requester `sato` (`REQUESTER`, `departments: [sales]`), procurement
`hara`/`ota` (`PROCUREMENT` / `+PROCUREMENT_HEAD`), suppliers `kita`/`minami`
(`SUPPLIER`, `partner: P-100` / `P-200`) — then:

1. **sato** creates a requisition and submits: the `approvalRoute` decision stamps the
   lane (capital categories and large amounts go two-stage), the department manager's
   task opens; the manager (and for two-stage, `ota`) approves.
2. **hara** turns it into an RFQ (`POST /api/rfqs` — an unapproved source answers 422),
   invites both partners, submits; **ota** issues — the quote-collection follow-up
   opens with a 168-hour reminder deadline.
3. **kita** and **minami** each see only their own invitation (`/api/supplier/rfqs`),
   start quotes (the lines copy from the requisition), price them, and submit — an
   unpriced submit is guarded, and neither can touch the other's rows (3204).
4. **hara** opens `/api/rfqs/{id}/comparison`, picks the *non-lowest* quote → a written
   reason is demanded (422); with the reason, the `orderApproval` decision routes the
   order to `ota`'s desk (within 3% it would have issued itself).
5. The supplier proposes a +3-day delivery date: within the tolerance table it
   auto-confirms. Tighten `/api/tolerances` to 1 day and the next proposal lands as a
   review task instead — **the decision reads the table live, no deploy**.
6. The supplier registers the shipment and ships (shipping without registering answers
   3204); **sato** receives — the chain closes, `/api/shipments/export` has the CSV,
   and `/dashboard` shows the story.

## Test it

```bash
tesseraql test --app .
```
