-- Appends one grant-change row (docs/access-governance.md structural decision 1). The
-- trail is append-only: nothing updates or deletes a row, so the insert is the only write
-- this table ever takes. Both grant write paths call it -- the admin edit and the sign-in
-- rule converge -- which is why the actor may be a mechanism name rather than a person.
insert into tql_grant_history (
  event_id, occurred_at, actor, subject_user_id, change_kind, subject_code,
  application, source, starts_at, ends_at, reason, correlation
) values (
  /* eventId */ 'evt-1',
  /* occurredAt */ '2026-08-20 09:00:00',
  /* actor */ 'admin',
  /* subjectUserId */ 'u1',
  /* changeKind */ 'role-granted',
  /* subjectCode */ 'orders.buyer',
  /* application */ 'orders',
  /* source */ 'admin',
  /* startsAt */ null,
  /* endsAt */ null,
  /* reason */ null,
  /* correlation */ null
)
;
