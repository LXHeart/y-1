-- Replace the placeholder values before executing manually.
insert into app_users (id, email, password_hash, display_name, role, status)
values (
  gen_random_uuid(),
  'admin@example.com',
  '$2b$12$replace_with_bcrypt_hash',
  '管理员',
  'admin',
  'active'
)
on conflict (email) do nothing;
