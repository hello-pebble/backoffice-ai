create table app_documents (
    document_key text primary key,
    payload jsonb not null,
    updated_at timestamptz not null default now()
);

create table automation_keywords (
    id bigserial primary key,
    keyword text not null,
    search_volume integer not null default 0,
    category text not null default '',
    collected_at timestamptz not null default now(),
    used boolean not null default false,
    priority integer not null default 0
);
create index automation_keywords_unused_idx on automation_keywords (used, priority desc, search_volume desc);

create table automation_contents (
    id uuid primary key,
    keyword text not null,
    title text not null,
    content text not null,
    tags jsonb not null default '[]'::jsonb,
    created_at timestamptz not null default now(),
    status text not null default 'pending',
    posted_at timestamptz
);
create index automation_contents_status_idx on automation_contents (status, created_at desc);

create table automation_posting_history (
    id uuid primary key,
    content_id uuid not null references automation_contents(id),
    blog_url text,
    posted_at timestamptz not null default now(),
    status text not null,
    error_message text,
    created_at timestamptz not null default now()
);
