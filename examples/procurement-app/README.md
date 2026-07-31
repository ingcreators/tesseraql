# procurement-app

The suite-scale gallery application (docs/procurement-demo.md): an end-to-end
buyer/supplier procurement flow built up in slices. **Slice 1** is in place — purchase
requisitions with decision-routed approval:

- a `kind: workflow` requisition document whose `submit` evaluates the shared
  **`approvalRoute` decision** (two inputs: amount × category) and stamps the lane;
  the manager lane approves in one step, the two-stage lane passes through the
  procurement head — every lane has exactly one path out;
- **row-level data scoping** (`scope/requisitions_scope.yml`): procurement sees every
  department, requesters and managers see their own (`departments` claim),
  deny-by-default for everyone else; lines ride the header's scope through the join;
- **field authorization**: the buyer-internal cost estimate is absent from the JSON API
  unless the caller clears `req.cost`; the HTML list simply never selects it.

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

## Test it

```bash
tesseraql test --app .
```
