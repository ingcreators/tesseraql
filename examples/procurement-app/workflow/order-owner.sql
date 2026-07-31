-- An out-of-tolerance proposal lands with whoever placed the order.
select ordered_by as assignee from orders where id = /* key */ 'ORD-0'
