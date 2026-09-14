-- #101 review: forward-only fixes. V1-V83 checksums remain unchanged.
ALTER TABLE creation_export
    ADD COLUMN IF NOT EXISTS build_started_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS build_token uuid;
UPDATE creation_export SET build_token = id WHERE build_token IS NULL;
ALTER TABLE creation_wechat_account ALTER COLUMN display_name TYPE varchar(80);
CREATE UNIQUE INDEX IF NOT EXISTS uq_creation_wechat_active_app
    ON creation_wechat_account (lower(app_id)) WHERE state <> 'disconnected';
-- Each sync retains its own ordered mapping; a cache hit must never move a row out of an older sync.
ALTER TABLE creation_wechat_media_mapping DROP CONSTRAINT IF EXISTS uq_creation_wechat_media;
CREATE INDEX IF NOT EXISTS creation_wechat_uploaded_content_idx
    ON creation_wechat_media_mapping (account_id, account_version, content_hash, purpose) WHERE state = 'uploaded';

DO $$
DECLARE rule record;
BEGIN
    FOR rule IN SELECT * FROM (VALUES
        ('creation_source_document','ck_studio_source_limits',
         'char_length(raw_text) BETWEEN 1 AND 30000 AND jsonb_typeof(blocks_json) = ''array'' AND jsonb_array_length(blocks_json) <= 500 AND jsonb_typeof(source_refs_json) = ''array'' AND jsonb_array_length(source_refs_json) <= 20'),
        ('creation_text_proposal','ck_studio_proposal_versions',
         'base_draft_version > 0 AND (applied_draft_version IS NULL OR applied_draft_version > 0)'),
        ('creation_visual_plan','ck_studio_plan_version','base_draft_version > 0'),
        ('creation_visual_quote','ck_studio_quote_revision','plan_revision > 0'),
        ('creation_visual_item','ck_studio_item_position','position BETWEEN 1 AND 9'),
        ('creation_visual_artifact','ck_studio_artifact_revision','plan_revision > 0'),
        ('creation_studio_apply','ck_studio_apply_version','applied_draft_version > 0'),
        ('creation_export','ck_studio_export_state','state IN (''building'',''ready'',''failed'')'),
        ('creation_export','ck_studio_export_version','version > 0'),
        ('creation_export','ck_studio_export_format','format IN (''markdown'',''text'',''wechat-html'',''bundle-zip'')'),
        ('creation_export','ck_studio_export_theme','theme IN (''standard'',''compact'')'),
        ('creation_wechat_account','ck_studio_wechat_account_state','state IN (''unverified'',''active'',''invalid'',''disconnected'')'),
        ('creation_wechat_account','ck_studio_wechat_account_version','version > 0'),
        ('creation_wechat_draft_sync','ck_studio_wechat_sync_versions','version > 0 AND draft_version > 0 AND account_version > 0'),
        ('creation_wechat_media_mapping','ck_studio_wechat_media_versions','account_version > 0 AND ordinal >= 0 AND attempts >= 0')
    ) AS rules(table_name, constraint_name, expression)
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = rule.constraint_name
                       AND conrelid = rule.table_name::regclass) THEN
            EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (%s)',
                           rule.table_name, rule.constraint_name, rule.expression);
        END IF;
    END LOOP;
END $$;
