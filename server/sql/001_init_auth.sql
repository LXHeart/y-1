create extension if not exists pgcrypto;

create table if not exists schema_migrations (
  version text primary key,
  applied_at timestamptz not null default now()
);

create table if not exists app_users (
  id uuid primary key,
  email text not null unique,
  password_hash text not null,
  display_name text,
  role text not null default 'admin',
  status text not null default 'active',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  last_login_at timestamptz
);

create index if not exists idx_app_users_status on app_users (status);
create index if not exists idx_app_users_created_at on app_users (created_at desc);

create table if not exists user_settings (
  id uuid primary key,
  user_id uuid not null references app_users(id) on delete cascade,
  settings_type text not null,
  settings_json jsonb not null,
  version integer not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint user_settings_type_check check (settings_type in ('analysis', 'homepage')),
  constraint user_settings_unique_user_type unique (user_id, settings_type)
);

create index if not exists idx_user_settings_user_id on user_settings (user_id);
create index if not exists idx_user_settings_type on user_settings (settings_type);
