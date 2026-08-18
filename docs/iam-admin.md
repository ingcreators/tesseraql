# IAM Admin

IAM Admin is the console for **people**: who has an account, who is signed in right now,
and who is standing in for whom. It is the administrative counterpart to the self-service
[account surface](account.md), where users manage their own profile and sessions.

Open it at `/_tesseraql/admin/users`. In a hosted stack it answers once, at the stack's
origin — the identity store is stack-scoped, so there is one admin door to it, exactly as
there is one sign-in door ([hosting.md](hosting.md)). An unhosted runtime serves its own
copy as before.

## Who can open it

| Granted atom | Grants |
| --- | --- |
| `tql.iam.admin.view` | The user list, user detail, the sessions page, and delegations. |
| `tql.iam.admin.write` | Inviting, enabling and disabling users, and revoking sessions. |

These are framework atoms — permission codes in the identity store, granted to a user
directly or bundled into whatever roles your deployment defines
([authentication.md](authentication.md)). They are store-wide exact strings: the store has
no per-application axis, so neither do they.

## Users

The user list shows the accounts in the identity store with their status. Open one to
reach the detail page, which is where most administrative work happens.

**Invite a user.** The invitation creates the account and sends the new user a link to set
their own password. Nobody types a password on someone else's behalf. The invitation and
reset flows are described in [credential-lifecycle.md](credential-lifecycle.md).

**Disable a user.** Disabling ends access immediately: it marks the account disabled *and
invalidates every session that account holds*. A disabled account is not "disabled at next
login" — the browser it left open stops working on the next request.

**Enable a user.** Restores access. The user signs in again as normal.

## Sessions

Two views cover live sessions:

- The **user detail page** lists the sessions that one account holds, with the time each
  was created, when it was last seen, and the user agent and address it was presented
  from. You can end one session, or end all of that account's sessions at once.
- The **sessions page** (`/_tesseraql/admin/sessions`) lists live sessions across every
  subject, for the question "who is signed in right now".

Sessions are addressed by a handle, never by the cookie value, so nothing on these screens
can be replayed as a credential. The address is recorded exactly as presented by the
request; the framework does not resolve it to a location.

Where `tesseraql.sessions.idleTimeout` is configured, a session also expires after that
much inactivity, inside its absolute lifetime. The scaffolded configuration sets it.

## Delegations

The delegations page shows who is currently acting for whom, and over what period.
Delegation lets an approver hand their workflow tasks to a colleague while they are away,
and this page is where an administrator sees the standing arrangements.

Delegation is declared and driven by the workflow layer; see
[delegation.md](delegation.md) for the model and [approval-workflow.md](approval-workflow.md)
for the approvals it applies to.

## What IAM Admin does not do

- **Roles and policies are declared in the application**, not edited here. They live in
  YAML and travel through review and promotion like the rest of the application
  ([authentication.md](authentication.md), [promotion.md](promotion.md)).
- **Federated users come from the identity provider.** With SAML ([saml.md](saml.md)) or
  SCIM provisioning, the directory is the source of truth for accounts and group
  membership; IAM Admin shows the result.
- **Row-level access** is [data scoping](data-scoping.md) and
  [multi-tenancy](multi-tenancy.md), declared in the application.

## Next

- [authentication.md](authentication.md) — realms, roles, policies, and how a principal is
  resolved.
- [account.md](account.md) — the self-service surface users see.
- [credential-lifecycle.md](credential-lifecycle.md) — invitations, password reset, and
  rate limiting.
