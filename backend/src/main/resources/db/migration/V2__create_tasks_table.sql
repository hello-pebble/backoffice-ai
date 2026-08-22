create table tasks (
    id text primary key,
    title text not null,
    team text not null,
    owner_name text not null,
    due_date date not null,
    status text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index tasks_due_date_idx on tasks (due_date);
