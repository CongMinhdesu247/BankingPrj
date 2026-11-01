-- Read-only checks. Expected: no rows in the first two result sets.
-- Pair completeness, direction, amount, currency and account association.
SELECT t.id, t.reference
FROM transactions t
LEFT JOIN transaction_entries e ON e.transaction_id = t.id
GROUP BY t.id
HAVING count(e.id) <> 2
    OR count(DISTINCT e.direction) <> 2
    OR sum(CASE WHEN e.direction = 'DEBIT' THEN -e.amount ELSE e.amount END) <> 0
    OR bool_or(e.amount <> t.amount OR e.currency <> t.currency
        OR (e.direction = 'DEBIT' AND e.account_id <> t.source_account_id)
        OR (e.direction = 'CREDIT' AND e.account_id <> t.destination_account_id));

-- Replace NULL/0 with documented opening-book reserves for your own dev fixture.
-- Existing banking_db has no opening reserve seeded by the application.
WITH opening_reserves(account_id, amount) AS (
    VALUES (NULL::uuid, 0::numeric)
), movements AS (
    SELECT account_id,
        sum(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) AS net
    FROM transaction_entries
    GROUP BY account_id
)
SELECT a.id, a.account_number, a.balance,
    coalesce(o.amount, 0) + coalesce(m.net, 0) AS expected_balance
FROM accounts a
LEFT JOIN opening_reserves o ON o.account_id = a.id
LEFT JOIN movements m ON m.account_id = a.id
WHERE a.balance <> coalesce(o.amount, 0) + coalesce(m.net, 0);

-- Compare total before/after a transfer in the same simulated account population.
SELECT sum(balance) AS total_balance FROM accounts;
