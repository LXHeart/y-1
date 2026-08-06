-- Historical control-plane rows predate max_concurrency validation.
-- Disable invalid active rows fail-closed, preserve the original value in history,
-- normalize the stored value, then validate the V14 constraint.

INSERT INTO platform_model_config_history(
    capability, model_role, provider, model, base_url, max_concurrency,
    health_status, version, changed_by, change_type, changed_at
)
SELECT capability, model_role, provider, model, base_url, max_concurrency,
       health_status, version, 'migration:v16', 'repair', now()
FROM platform_model_config
WHERE max_concurrency IS NOT NULL
  AND max_concurrency NOT BETWEEN 1 AND 1000;

UPDATE platform_model_config
SET enabled = false,
    max_concurrency = CASE
        WHEN max_concurrency < 1 THEN 1
        WHEN max_concurrency > 1000 THEN 1000
        ELSE max_concurrency
    END,
    version = version + 1,
    updated_by = 'migration:v16',
    updated_at = now()
WHERE max_concurrency IS NOT NULL
  AND max_concurrency NOT BETWEEN 1 AND 1000;

ALTER TABLE platform_model_config
    VALIDATE CONSTRAINT chk_platform_model_max_concurrency;

