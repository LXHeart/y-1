create table if not exists email_verification_codes (
  id uuid primary key default gen_random_uuid(),
  email text not null,
  code text not null,
  used boolean not null default false,
  expires_at timestamptz not null,
  created_at timestamptz not null default now()
);

create index if not exists idx_email_verification_email on email_verification_codes (email, code, used);
create index if not exists idx_email_verification_expires on email_verification_codes (expires_at);
