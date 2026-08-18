# The procurement demo application

> **Status: slices 1–7 implemented** (#523–#528 and the polish slice; slice 8, the EDI
> companion, remains an explicit open decision). The app lives at
> `examples/procurement-app`, held to the gallery bar. Composition findings the build
> surfaced — each fixed in the framework, never worked around: suites could not run
> scoped SQL (`principal:` on test cases + the production resolver moved to
> `tesseraql-identity`), suites lacked the managed framework schema (the runner now
> provisions it), `TQL-DECISION-4716` missed workflow `decide:` references, and a
> managed-mode transition whose command updated zero rows silently advanced
> (`TQL-WORKFLOW-3204` now enforces the documented row-authority contract). The
> comparison pivot stayed per-supplier totals by design — dynamic columns are outside
> plain-SQL reach; PDFs are deferred to a dedicated documents step resolving the
> `tesseraql-pdf` module story once for all three documents.

## Why this app exists

The template gallery (roadmap Phase 47) deliberately ships single-concept starters:
`purchase-request-app` is the smallest approval workflow, `inventory-app` the declarative
view surface, `helpdesk-app` the status-column workflow plus outbox. What no gallery app
answers is the question every framework evaluation actually turns on: *do the features
compose?* Decision tables routing a workflow whose documents are scoped by org unit, whose
transitions mail through the outbox, whose documents render to PDF, whose lists mask fields
per role — all in one application.

The app serves three purposes, in priority order:

1. **Pre-1.0 dogfooding at composition scale.** Defects that only appear when features
   interact (directive interplay, governance false positives, cross-document commands)
   must surface while breaking changes are still allowed (AGENTS.md rule 10). Friction
   found here is recorded as framework follow-ups, never worked around in app code —
   the same discipline rule 11 applies to Hypermedia Components gaps.
2. **The demo centerpiece.** A scripted three-login tour (requester, procurement,
   supplier) that walks one requisition from creation to goods receipt. This is the
   artifact talks, screenshots, and the docs site lead with.
3. **The "starter grows up" story.** The requisition slice deliberately starts from
   `purchase-request-app`'s shapes, showing that a gallery starter scales into a real
   system without changing idiom.

## The business flow

One document chain, three personas, buyer side and supplier side of the same portal:

```
requester      : purchase requisition ──▶ (approval: 1 or 2 stages, decision-routed)
procurement    : RFQ from approved requisition ──▶ (RFQ approval) ──▶ issued to N suppliers
supplier(s)    : quote per invited supplier (portal login)         [quote due deadline]
procurement    : quote comparison ──▶ purchase approval (decision-lane) ──▶ order issued
supplier       : order confirmation │ delivery-date proposal
buyer/decision : date accepted (auto within tolerance, else review) ──▶ confirmed
supplier       : shipment registered (delivery note PDF, CSV export)
requester      : goods receipt ──▶ chain closed
```

The three-persona structure is the point. It is what makes the security surface
demonstrable: a requester sees their own department's documents, procurement sees
everything including prices, a supplier sees only documents addressed to them and never
a competitor's quote.

## Non-goals (the fence against ERP scope creep)

Named explicitly because every one of these is a natural "while we're at it":

- **No split/partial shipments.** One order ships once. The lines model supports revisiting
  this later; v1 does not.
- **No invoicing, payment, or accounting.** The chain ends at goods receipt. Order-to-cash
  is a possible future companion, not this app.
- **No inventory.** `inventory-app` owns that concept.
- **No budget ledger.** Requisitions carry a free-text budget label; nothing checks balances.
- **No generic approval-route builder.** Routes are expressed as decision tables over
  amount and category. TesseraQL is not a BPM engine and the demo must not pretend
  otherwise; the pitch is that the typical cases are declarative.
- **No re-quote rounds or price negotiation.** One quoting round per RFQ. The delivery-date
  negotiation loop is the one negotiation the app models.
- **No EDI standard formats.** CSV with honest column names; the optional EDI slice moves
  files over SFTP but does not implement 全銀/CII/EDIFACT.
- **No proxy approval / delegation and no configurable rework targets.** Rejection returns
  a document to its author; that is the only rework path.
- **PostgreSQL only**, like every gallery app (`--embedded-db` is the demo path).

## Personas, organizations, security

Managed org units (`tesseraql.orgunit.mode: managed`) model the buyer side; suppliers are
partner rows tied to principals by claim, not org units:

- **Org tree:** `head-office` → `engineering`, `sales` (requesting departments), and
  `procurement`.
- **Roles:** `requester` (create/submit requisitions, receive goods), `procurement`
  (RFQs, comparison, orders; sees prices), `procurement-head` (approval lanes that
  escalate), `supplier` (quote, confirm, propose dates, ship — via the same portal).
- **Supplier identity:** each supplier principal carries a `partner` claim; every
  supplier-visible table carries `partner_id`.

Scopes (docs/data-scoping.md), declared once and applied via `/*%scope */`:

- `ownDepartment` — requisitions visible to their department subtree (requester lists).
- `ownPartner` — `partner_id = :principal.partner` (supplier lists: invited RFQs, own
  quotes, own orders/shipments). This scope is what makes "a supplier never sees a
  competitor's quote" a declaration instead of a code-review hope.

Field policies / masking:

- `internal_estimate` on requisitions and `budget_label` are hidden from `supplier`.
- Unit costs on quotes are visible to `procurement`/`procurement-head` only; the
  requester sees quantities and dates but masked prices on the detail view — masking
  demonstrated on a screen the tour actually visits, not a synthetic example.

Console access rides the standard per-app scope (`tql.ops.view.procurement`).

## Data model

Postgres, one migration chain, seeded demo data in Japanese (company/item names, dates,
plausible amounts) — the seed quality is demo material, not filler. Managed workflow
state, tasks, and history live in `tql_workflow_*`; the app tables stay business-only.

| Table | Purpose / notable columns |
|---|---|
| `partners` | supplier master: name, contact email |
| `items` | item master: name, unit, category |
| `purchase_requisitions` | requester dept, `category`, `amount`, `budget_label`, `internal_estimate`, `approval_route` (stamped lane) |
| `requisition_lines` | item, qty, desired delivery date |
| `rfqs` | from an approved requisition; quote due date |
| `rfq_suppliers` | invitation: `rfq_id` × `partner_id` |
| `quotes` | per invited supplier; `status` column (`draft`/`submitted`) — app-mode, not a managed workflow |
| `quote_lines` | unit price, promised delivery date per line |
| `orders` | from the selected quote; `selection_reason`, `approval_lane` (stamped), current promised date |
| `order_lines` | copied from quote lines |
| `date_change_requests` | negotiation history: proposed date, `slip_days`, resolution |
| `shipments` | one per order (v1); ship date, carrier, delivery-note number |

Twelve tables. Requisitions, RFQs, and orders are managed-workflow documents; quotes are
deliberately a plain status column (the `helpdesk-app` pattern) so the suite demonstrates
both workflow modes side by side.

## Workflows

Three `kind: workflow` documents. State machines kept small enough to draw in the README.

**`requisition`** — `draft → submitted → (mgr_approved →)? approved | rejected`, plus
`rejected → draft` rework. The `submit` transition evaluates the `approvalRoute` decision
and stamps the lane; the two approve paths guard on the stamped lane
(`document.approval_route == 'manager'` goes straight to `approved`; `'two_stage'` passes
through `mgr_approved` with a second task assigned to procurement-head). The enum-output
lints (`TQL-DECISION-4712/4713`) prove at build time that every lane the decision can
produce has a transition — the "unhandled else" caught statically is itself a demo point.

**`rfq`** — `draft → submitted → issued → closed`. `submitted → issued` is the RFQ
approval (procurement-head). The `issued` state carries a **deadline** (the quote due
date) with escalation — reminder mail to unanswered suppliers through the outbox. This
uses the workflow engine's per-state deadlines (docs/approval-workflow.md), not a
hand-rolled job; the framework already owns this machinery and the demo must show it.

**`order`** — `draft → submitted → issued → confirmed | date_proposed → confirmed →
shipped → received`, plus `rejected` lanes on approval. Creation happens from the quote
comparison screen: the create command is an insert-from-select over the chosen quote
(cross-document write inside one transaction — a deliberate composition probe), evaluates
`quoteSelectionApproval`, and stamps the approval lane (`auto` lanes skip straight to
`issued`). Supplier transitions: `confirm`, `propose_date` (writes a
`date_change_requests` row), `ship`. The `date_proposed → confirmed` edge evaluates the
table-backed `deliveryAutoAccept` decision: within tolerance auto-confirms, outside it
assigns a review task to the buyer. `issued` carries a confirmation deadline.

## The three decision tables

The showpieces, one per consumption pattern, all three visited by the tour:

1. **`approvalRoute`** (YAML rows — release-versioned policy): `amount` (`between`) ×
   `category` (`in`) → `route: [manager, two_stage]`. Consumed by the requisition
   workflow. The archetype promoted from `purchase-request-app`, now with a second input
   dimension.
2. **`quoteSelectionApproval`** (YAML rows): `is_lowest` (`bool`) × `delta_pct`
   (`between`) → `lane: [auto, head_review]`. Selecting the lowest quote auto-approves;
   selecting a non-lowest quote requires a `selection_reason` (enforced by a validation
   rule guarded `when: decision.quoteSelectionApproval.lane == 'head_review'`) and routes
   to procurement-head. Governance philosophy in one table: deviation is allowed,
   recorded, and approved — never silently blocked.
3. **`deliveryAutoAccept`** (**table-backed** `source:` — runtime-maintained): `slip_days`
   (`between`) → `action: [auto_confirm, review]`. The tolerance is business data, not
   release policy, so it lives in an app table maintained through a `scaffold decision` +
   `scaffold crud` surface by procurement — demonstrating the decision design's second
   row source and its maintenance story in one step.

## Views, documents, files

- **Per-persona menus** (`config/menu.yml` + role visibility): requester (my requisitions,
  goods receipt), procurement (approval inbox, RFQs, comparison, orders, decision
  maintenance), supplier (invited RFQs, my quotes, my orders, shipping).
- **Declarative views** for every list/detail/form, including a procurement dashboard
  (open requisitions, unanswered RFQs, delayed confirmations — the deadline data).
- **Quote comparison** — one screen, suppliers as columns, lines as rows. This is the one
  view expected to strain the declarative-view surface (pivoting across a variable set of
  suppliers). Design stance: attempt it declaratively; if it does not fit, that is a
  *framework finding* — file the views follow-up and fall back to a `page` recipe with an
  app template. Do not bend the view compiler from inside the app.
- **PDFs** via `query-export` + `format: pdf` (`tesseraql-pdf`): quote (見積書), order
  (注文書), delivery note (納品書).
- **CSV** via `file-export`: shipment results. The optional EDI slice re-uses exactly this
  export, moved over SFTP, imported by `file-import` on the other side.
- **Mail** via the outbox: RFQ issued (per invited supplier), order issued, shipment
  registered, deadline escalations.
- **i18n**: Japanese-first messages and seed data with English as the second locale;
  the tour switches language once to show it (M13 machinery).

## Tests, coverage, admission

Held to the same bar as the rest of the gallery, scaled up:

- A declarative suite that walks the full chain — one scenario per slice plus the
  end-to-end tour scenario; `decide:` test cases for all three decision tables
  (the table-backed one against the suite datasource).
- Coverage kinds exercised: SQL line/branch, route, security (the scope and masking
  declarations), decision, workflow transitions.
- `tesseraql admission --app .` clean; the suite runs in CI like the other gallery apps
  (regen discipline: scaffold-derived surfaces are regenerated, never hand-edited).
- The query plan guard on the comparison and dashboard queries — the two most
  join-heavy statements.

## The demo tour (the deliverable, not an afterthought)

A scripted walkthrough in the README, three browser sessions:

1. **Requester** (engineering) creates a requisition (2 lines, ¥480,000) → submits;
   `approvalRoute` says `two_stage`; manager approves, procurement-head approves.
2. **Procurement** turns it into an RFQ, invites two suppliers, head approves issue;
   both suppliers get mail.
3. **Supplier A** logs in, sees only their invitation, submits a quote (見積書 PDF).
   Supplier B quotes higher. Neither can see the other.
4. **Procurement** opens the comparison, picks Supplier B (non-lowest) → reason required,
   `head_review` lane; head approves; 注文書 PDF; order mail.
5. **Supplier B** proposes a date 3 days late → `deliveryAutoAccept` auto-confirms
   (tolerance row: ≤5 days). A second demo path proposes 14 days → buyer review task.
6. **Supplier B** ships (納品書 PDF, CSV export) → **requester** receives → chain closed.
7. Finale: the ops console — traces of the whole chain, the outbox, slow-SQL page over
   the seeded volume; then the decision maintenance screen changing the tolerance live.

## Slices

Each slice lands green with its own suite scenarios, in the standing PR discipline:

1. **Requisition + routed approval** — tables, requisition workflow, `approvalRoute`
   decision (two inputs), org scoping, masking of internal fields. Starts from
   `purchase-request-app` shapes.
2. **RFQ + invitations + RFQ approval** — rfq workflow, `rfq_suppliers`, quote-due
   deadline + escalation mail.
3. **Supplier portal: quotes** — supplier role/claim + `ownPartner` scope, quote
   status-column flow, quote PDF.
4. **Comparison + purchase approval + order** — comparison view (the declarative-surface
   probe), `quoteSelectionApproval` + selection-reason rule, insert-from-select order
   creation, order PDF + mail.
5. **Confirmation + delivery-date negotiation** — confirm/propose transitions,
   `date_change_requests`, table-backed `deliveryAutoAccept` + its scaffolded
   maintenance surface, confirmation deadline.
6. **Shipment + receipt** — ship/receive transitions, delivery-note PDF, shipment CSV
   export, procurement dashboard.
7. **Demo polish** — Japanese seed data pass, i18n second locale, tour README with
   screenshots, admission + end-to-end suite scenario, docs-site gallery entry.
8. *(optional, separate decision)* **EDI companion** — a minimal supplier-side app
   exchanging the shipment CSV over SFTP (`file-export` → SFTP → `file-import`),
   exercising the poll-source hardening in a demo-visible way.

Slices 1–7 are the commitment; slice 8 is named so it is a decision, not scope drift.

## Composition probes (what this app is expected to teach us)

Recorded up front so findings are measured against expectations:

- Three managed workflows plus a status-column flow in one app (manifest scale, symbols,
  Studio and portal rendering at this size).
- A decision consumed across documents and a decision evaluated inside a transition that
  *creates* another workflow's document (order from quote).
- The comparison pivot as a declarative-views stress test.
- Scope + masking + workflow assignment all keyed off the same principal in one request
  path (the supplier portal read path).
- Deadline/escalation machinery driven by business dates from documents.
- Governance lints (route defaults, scope inference TQL-SEC-4100, decision lints) at
  suite scale — false-positive hunting on a bigger surface than the starters.

Any friction lands as a framework issue first; the app works around nothing silently.
