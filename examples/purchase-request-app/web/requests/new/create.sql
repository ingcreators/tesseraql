insert into purchase_requests (id, title, amount, supplier_id, requested_by)
values ('PR-' || substr(gen_random_uuid()::text, 1, 8),
        /* title */ 'Example', /* amount */ 1, /* supplier_id */ 'sup-100',
        /* audit.user */ 'someone')
