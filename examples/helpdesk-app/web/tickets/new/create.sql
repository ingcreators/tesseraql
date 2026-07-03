insert into tickets (id, subject, priority, requester)
values ('T-' || substr(gen_random_uuid()::text, 1, 8),
        /* subject */ 'Example', coalesce(/* priority */ 'normal', 'normal'),
        /* audit.user */ 'someone')
