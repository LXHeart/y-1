create table if not exists cached_hot_topics (
  id uuid primary key default gen_random_uuid(),
  provider text not null default '60s',
  items jsonb not null,
  fetched_at timestamptz not null default now()
);

create index if not exists idx_cached_hot_topics_provider on cached_hot_topics (provider);
