-- Existing V9 active tokens get a rollout grace lease. Identity renews legitimate
-- references through the new idempotent PUT contract; abandoned tokens converge.
UPDATE media_kyb_retention
SET lease_until = now() + interval '30 days',
    updated_at = now()
WHERE released_at IS NULL
  AND lease_until IS NULL
  AND retained_until IS NULL;
