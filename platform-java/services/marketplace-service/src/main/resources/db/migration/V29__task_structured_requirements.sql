-- PRD 4.12: editable structured requirements on the current task row; task_version remains immutable.
ALTER TABLE task
    ADD COLUMN requirements jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD CONSTRAINT chk_task_requirements_object CHECK (jsonb_typeof(requirements) = 'object');

-- Preserve any requirements that were already written directly to historical task versions.
UPDATE task current_task
SET requirements = latest.requirements
FROM (
    SELECT DISTINCT ON (task_id) task_id, requirements
    FROM task_version
    ORDER BY task_id, version DESC
) latest
WHERE latest.task_id = current_task.id
  AND latest.requirements <> '{}'::jsonb;
