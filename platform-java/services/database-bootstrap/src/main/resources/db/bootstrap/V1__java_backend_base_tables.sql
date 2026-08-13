CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS app_users (
    id uuid PRIMARY KEY,
    email text NOT NULL UNIQUE,
    password_hash text NOT NULL,
    display_name text,
    role text NOT NULL DEFAULT 'user',
    status text NOT NULL DEFAULT 'active',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    last_login_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_app_users_status ON app_users(status);
CREATE INDEX IF NOT EXISTS idx_app_users_created_at ON app_users(created_at DESC);

CREATE TABLE IF NOT EXISTS session (
    sid varchar PRIMARY KEY,
    sess json NOT NULL,
    expire timestamp(6) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_session_expire ON session(expire);

CREATE TABLE IF NOT EXISTS user_settings (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    settings_type text NOT NULL,
    settings_json jsonb NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT user_settings_type_check
        CHECK (settings_type IN ('analysis', 'homepage', 'image-review-style')),
    CONSTRAINT user_settings_unique_user_type UNIQUE (user_id, settings_type)
);

CREATE INDEX IF NOT EXISTS idx_user_settings_user_id ON user_settings(user_id);
CREATE INDEX IF NOT EXISTS idx_user_settings_type ON user_settings(settings_type);

CREATE TABLE IF NOT EXISTS email_verification_codes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email text NOT NULL,
    code text NOT NULL,
    used boolean NOT NULL DEFAULT false,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_email_verification_email
    ON email_verification_codes(email, code, used);
CREATE INDEX IF NOT EXISTS idx_email_verification_expires
    ON email_verification_codes(expires_at);

ALTER TABLE app_users ALTER COLUMN role SET DEFAULT 'user';

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.user_settings'::regclass
          AND conname = 'user_settings_type_check'
    ) THEN
        ALTER TABLE user_settings DROP CONSTRAINT user_settings_type_check;
    END IF;
    ALTER TABLE user_settings ADD CONSTRAINT user_settings_type_check
        CHECK (settings_type IN ('analysis', 'homepage', 'image-review-style'));
END
$migration$;
