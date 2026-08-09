# Build an approval application

A request is raised, someone reviews it, and it moves on: purchase requests, expense claims,
leave applications, change requests. This guide is the reading order for building one. Each
step links to the page that covers it; nothing here repeats those pages.

Start from [your first app](your-first-app.md) — you want the scaffolded CRUD loop working
before adding states to it.

## The shape you are building

A **business document** with states, transitions driven by human decisions, tasks assigned to
approvers, notifications when something needs attention, and a history a reviewer can read.

`examples/purchase-request-app` is the smallest complete version, and
`examples/procurement-app` is the same idea at suite scale, with four workflows.

## The order to read

**1. Model the document and its states.**
[approval-workflow.md](approval-workflow.md) is the main page. A `kind: workflow` document
declares the states and the transitions between them, and the framework synthesizes the
transition endpoints. Read it before writing any routes, because the workflow shapes them.

**2. Decide what may move, and when.**
Guards refuse a transition that should not happen — an amount over the approver's limit, a
document someone else already took. [declarative-validation.md](declarative-validation.md)
covers the rules, and decision tables carry policy that changes without a code change.

**3. Put the work in front of people.**
Tasks are assigned by the transition; [inbox.md](inbox.md) is where an approver sees what is
waiting, and [notifications.md](notifications.md) sends the mail that brings them back.

**4. Cover the absence case.**
Approvals stall when an approver is away. [delegation.md](delegation.md) lets someone stand in
for them, and IAM Admin shows the standing arrangements
([iam-admin.md](iam-admin.md)).

**5. Build the screens.**
[declarative-views.md](declarative-views.md) covers the list, the detail with its history
panel, and the form. Most approval screens need no template of your own.

**6. Prove it.**
[testing.md](testing.md) exercises transitions declaratively, including the ones that must be
refused. A workflow with untested guards is a workflow with no guards.

## What people usually get wrong

- **Writing the states as a `status` column and if-statements.** Declare the workflow instead;
  the transitions, guards, history, and endpoints all follow from the declaration.
- **Sending the notification from the command.** Declare it under `notify:` so it rides the
  transactional outbox and cannot be sent for a transition that rolled back.
- **Forgetting the concurrent approval.** Two approvers pressing the button at once is normal.
  The `expect: rowCount` check turns the loser into a clean 409 rather than a double approval.

## Next

- [approval-workflow.md](approval-workflow.md) — the main page for this shape.
- [delegation.md](delegation.md) — standing in for an absent approver.
- [testing.md](testing.md) — exercising transitions and their refusals.
