create table job_delivery (
  event_uuid varchar(64) primary key,
  job_id bigint not null,
  payload_json jsonb not null,
  received_at timestamptz not null default now()
);
create table publish_record (
  id uuid primary key,
  task_id uuid not null unique references repair_task(id),
  attempt_id uuid not null unique references repair_attempt(id),
  state varchar(32) not null,
  artifact_sha256 char(64) null,
  patch_sha256 char(64) null,
  branch_name text null,
  commit_sha char(40) null,
  merge_request_iid bigint null,
  merge_request_url text null,
  failure_code varchar(128) null,
  failure_detail text null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create table publish_step (
  publish_id uuid not null references publish_record(id),
  step varchar(32) not null,
  state varchar(16) not null,
  idempotency_key varchar(256) not null,
  detail_json jsonb not null,
  started_at timestamptz not null,
  finished_at timestamptz null,
  primary key(publish_id, step),
  unique(idempotency_key)
);
