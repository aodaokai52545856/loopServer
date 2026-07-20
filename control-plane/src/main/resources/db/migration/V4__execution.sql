create table repair_attempt (
  id uuid primary key,
  task_id uuid not null references repair_task(id),
  attempt_no integer not null,
  node_id uuid not null references repair_node(id),
  reservation_id uuid not null references task_reservation(id),
  pipeline_id bigint not null,
  job_id bigint not null,
  state varchar(32) not null,
  task_token_hash char(64) not null unique,
  lease_expires_at timestamptz not null,
  started_at timestamptz not null,
  finished_at timestamptz null,
  outcome_code varchar(128) null,
  unique(task_id, attempt_no),
  unique(job_id)
);
create table task_event (
  attempt_id uuid not null references repair_attempt(id),
  seq bigint not null,
  event_time timestamptz not null,
  received_at timestamptz not null default now(),
  type varchar(128) not null,
  payload_json jsonb not null,
  primary key(attempt_id, seq)
);
create index ix_task_event_time on task_event(attempt_id, event_time);
