insert into purchase_requests (id, title, amount, requested_by)
values ('PR-' || substr(gen_random_uuid()::text, 1, 8),
        /* title */ 'Example', /* amount */ 1, /* audit.user */ 'someone')
