-- Emails are stored trimmed and lowercased (User.email normalizes every write, lookups normalize
-- their argument), so letter case or stray spaces can't split one person across accounts.

-- Two accounts whose emails differ only in case or padding would collide on the unique constraint.
-- Refuse to run (the deploy fails, nothing changes) until they are merged by hand; the count only,
-- since emails are PII and this message lands in logs. Find them with:
--   SELECT LOWER(TRIM(email)), COUNT(*) FROM users GROUP BY 1 HAVING COUNT(*) > 1;
DO $$
DECLARE
    clashes INTEGER;
BEGIN
    SELECT COUNT(*) INTO clashes FROM (
        SELECT 1 FROM users GROUP BY LOWER(TRIM(email)) HAVING COUNT(*) > 1
    ) duplicated;
    IF clashes > 0 THEN
        RAISE EXCEPTION 'V55: % email(s) belong to more than one account once lowercased; merge those accounts first', clashes;
    END IF;
END $$;

UPDATE users SET email = LOWER(TRIM(email)) WHERE email <> LOWER(TRIM(email));
