-- legacy auth schema for identity-service tests (mirrors server/sql/001 & 002)
CREATE TABLE app_users (
  id            uuid PRIMARY KEY,
  email         text NOT NULL UNIQUE,
  password_hash text NOT NULL,
  display_name  text,
  role          text NOT NULL DEFAULT 'user',
  status        text NOT NULL DEFAULT 'active',
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  last_login_at timestamptz,
  deleted_at    timestamptz
);
CREATE TABLE session (
  sid    varchar NOT NULL PRIMARY KEY,
  sess   json NOT NULL,
  expire timestamp(6) NOT NULL
);
CREATE TABLE backend_role (
  account_id uuid NOT NULL,
  role       varchar(32) NOT NULL,
  granted_at timestamptz NOT NULL DEFAULT now(),
  granted_by uuid,
  PRIMARY KEY (account_id, role)
);
-- 任务书 #48：/api/auth/me 响应带首登强制改密标记（identity V42）
CREATE TABLE account_flag (
  account_id           uuid PRIMARY KEY,
  must_change_password boolean NOT NULL DEFAULT false,
  updated_at           timestamptz NOT NULL DEFAULT now()
);
-- 任务书 #49：登录名旁表（identity V44）——登录标识双查/用户名展示都读它；
-- 本 schema 的 app_users 手工建表晚于 Flyway，故必须在此同步声明（D14 双态约束）。
CREATE TABLE account_username (
  account_id uuid PRIMARY KEY,
  username   text NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now()
);
