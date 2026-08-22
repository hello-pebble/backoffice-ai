create table dashboard_kpis (id smallint primary key default 1 check (id = 1), revenue bigint not null default 0, expense bigint not null default 0, target bigint not null default 0, week_change double precision not null default 0, updated_at timestamptz not null default now());

create table approvals (id text primary key, type text not null, title text not null, requester text not null, amount bigint, status text not null, requested_at date not null, updated_at timestamptz not null default now());
create index approvals_status_idx on approvals (status, requested_at desc);

create table automation_runs (id text primary key, mode text not null, success boolean not null, exit_code integer, executed_at timestamptz not null, output text not null);
create index automation_runs_executed_at_idx on automation_runs (executed_at desc);

create table ai_news_items (id text primary key, source text not null, title text not null, url text not null unique, summary text not null, published_at text, category text not null, is_read boolean not null default false, collected_at timestamptz not null);
create index ai_news_items_collected_at_idx on ai_news_items (collected_at desc);

create table ai_news_briefings (id uuid primary key, generated_at timestamptz not null, model text not null, news_ids jsonb not null, items jsonb not null);
create index ai_news_briefings_generated_at_idx on ai_news_briefings (generated_at desc);

create table ai_operation_runs (id text primary key, executed_at timestamptz not null, agent text not null, provider text not null, model text not null, status text not null, duration_ms bigint not null, input_tokens bigint not null, output_tokens bigint not null, estimated_cost_usd double precision not null, tools jsonb not null, result_preview text not null, error text);
create index ai_operation_runs_executed_at_idx on ai_operation_runs (executed_at desc);

create table content_packages (id text primary key, title text not null, source text not null, tone text not null, target text not null, created_at timestamptz not null);
create table content_outputs (id bigserial primary key, package_id text not null references content_packages(id) on delete cascade, channel text not null, title text not null, body text not null);

