-- 任务书 #101 C101-19：公众号连接（V82）。
-- appId 在 owner 内部分唯一（另一 owner 已绑同 appId → 409 由服务层校验）；
-- 凭据只存信封加密密文；断开清密文保留同步记录；版本乐观锁。
CREATE TABLE IF NOT EXISTS creation_wechat_account (
    id                uuid PRIMARY KEY,
    owner_account_id  text NOT NULL,
    display_name      varchar(64) NOT NULL,
    app_id            varchar(64) NOT NULL,
    encrypted_secret  text,
    secret_key_version varchar(16),
    state             varchar(16) NOT NULL DEFAULT 'unverified',
    version           integer NOT NULL DEFAULT 1,
    verified_at       timestamptz,
    error_code        varchar(64),
    last_request_id   text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_creation_wechat_owner_app UNIQUE (owner_account_id, app_id)
);
CREATE INDEX IF NOT EXISTS creation_wechat_account_app_idx
    ON creation_wechat_account (app_id);
