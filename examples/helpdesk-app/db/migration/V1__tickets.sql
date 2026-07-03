-- Helpdesk tickets: app-mode workflow state lives in the status column.
create table tickets (
  id varchar(64) primary key,
  subject varchar(200) not null,
  priority varchar(16) not null default 'normal',
  requester varchar(120) not null,
  assignee varchar(120),
  status varchar(32) not null default 'open',
  created_at timestamp not null default now(),
  updated_at timestamp not null default now()
);

insert into tickets (id, subject, priority, requester, status) values
  ('T-2001', 'VPN drops every hour', 'high', 'aoki', 'open'),
  ('T-2002', 'Request: second monitor', 'normal', 'sato', 'open'),
  ('T-2003', 'Printer jam on 4F', 'low', 'tanaka', 'triaged');
