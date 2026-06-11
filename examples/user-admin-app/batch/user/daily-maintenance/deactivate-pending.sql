update
  users
set
  status = 'INACTIVE'
where
  status = 'PENDING'
;
