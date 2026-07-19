create table gitlab_webhook_delivery (
  event_uuid varchar(64) primary key,
  event_name varchar(64) not null,
  payload_json jsonb not null,
  received_at timestamptz not null,
  processing_state varchar(16) not null default 'PENDING',
  attempt_count integer not null default 0,
  next_attempt_at timestamptz not null default now(),
  processed_at timestamptz null,
  last_error text null
);
create index ix_webhook_pending
  on gitlab_webhook_delivery(next_attempt_at)
  where processing_state in ('PENDING', 'RETRY');

create table defect (
  id uuid primary key,
  intake_project_id bigint not null,
  issue_iid bigint not null,
  issue_global_id bigint not null,
  issue_url text not null,
  title text not null,
  description text not null,
  state varchar(32) not null,
  missing_fields_json jsonb not null default '[]',
  source_updated_at timestamptz not null,
  version bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(intake_project_id, issue_iid)
);

create table defect_transition (
  id bigserial primary key,
  defect_id uuid not null references defect(id),
  from_state varchar(32) null,
  to_state varchar(32) not null,
  reason varchar(128) not null,
  source_event_uuid varchar(64) null,
  created_at timestamptz not null default now()
);
create table defect_attachment (
  id uuid primary key,
  defect_id uuid not null references defect(id),
  source_url text not null,
  name text not null,
  content_type varchar(255) null,
  size_bytes bigint null,
  sha256 char(64) null,
  source_updated_at timestamptz not null,
  unique(defect_id, source_url)
);
