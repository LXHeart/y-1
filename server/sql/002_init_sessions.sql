create table if not exists session (
  sid varchar not null primary key,
  sess json not null,
  expire timestamp(6) not null
);

create index if not exists idx_session_expire on session (expire);
