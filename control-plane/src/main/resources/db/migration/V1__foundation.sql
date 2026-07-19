create table outbox_event (
  id uuid primary key,
  aggregate_type varchar(64) not null,
  aggregate_id varchar(128) not null,
  event_type varchar(128) not null,
  payload_json jsonb not null,
  occurred_at timestamptz not null,
  available_at timestamptz not null default now(),
  attempt_count integer not null default 0,
  processed_at timestamptz null,
  last_error text null
);
create index ix_outbox_pending on outbox_event (available_at) where processed_at is null;

create table audit_log (
  id bigserial primary key,
  actor_type varchar(32) not null,
  actor_id varchar(128) not null,
  action varchar(128) not null,
  object_type varchar(64) not null,
  object_id varchar(128) not null,
  request_id varchar(64) not null,
  detail_json jsonb not null,
  created_at timestamptz not null default now()
);
