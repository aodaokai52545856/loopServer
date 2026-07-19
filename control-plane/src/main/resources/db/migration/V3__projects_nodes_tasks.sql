create table project_profile (
  id uuid primary key,
  project_key varchar(128) not null unique,
  gitlab_path text not null unique,
  active_revision bigint null,
  created_at timestamptz not null default now()
);
create table project_profile_revision (
  profile_id uuid not null references project_profile(id),
  revision bigint not null,
  config_json jsonb not null,
  config_sha256 char(64) not null,
  published_by varchar(128) not null,
  published_at timestamptz not null default now(),
  primary key(profile_id, revision)
);
create table project_profile_owner (
  profile_id uuid not null references project_profile(id),
  gitlab_user_id bigint not null,
  added_by varchar(128) not null,
  added_at timestamptz not null default now(),
  primary key(profile_id, gitlab_user_id)
);

create table node_invite (
  id uuid primary key,
  code_hash char(64) not null unique,
  allowed_projects_json jsonb not null,
  expires_at timestamptz not null,
  used_at timestamptz null,
  used_by_node uuid null,
  created_by varchar(128) not null,
  created_at timestamptz not null default now()
);
create table repair_node (
  id uuid primary key,
  name varchar(128) not null,
  owner_id varchar(128) not null,
  description text not null default '',
  public_key_sha256 char(64) not null unique,
  certificate_serial varchar(128) not null unique,
  runner_id bigint null,
  runner_tag varchar(128) not null unique,
  state varchar(32) not null,
  enabled boolean not null default true,
  desired_revision bigint not null default 1,
  applied_revision bigint not null default 0,
  desired_config_json jsonb not null,
  concurrency_limit integer not null check(concurrency_limit between 1 and 10),
  active_slots integer not null default 0,
  allowed_projects_json jsonb not null,
  capabilities_json jsonb not null,
  last_heartbeat_at timestamptz null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create table node_heartbeat (
  node_id uuid not null references repair_node(id),
  observed_at timestamptz not null,
  metrics_json jsonb not null,
  primary key(node_id, observed_at)
);

create table repair_task (
  id uuid primary key,
  defect_id uuid not null references defect(id),
  defect_revision bigint not null,
  project_key varchar(128) not null,
  profile_revision bigint not null,
  profile_snapshot_json jsonb not null,
  base_sha char(40) not null,
  state varchar(32) not null,
  priority integer not null default 100,
  requested_node_id uuid null references repair_node(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(defect_id, defect_revision)
);
create table task_reservation (
  id uuid primary key,
  task_id uuid not null unique references repair_task(id),
  node_id uuid not null references repair_node(id),
  pipeline_id bigint null,
  expires_at timestamptz not null,
  state varchar(16) not null,
  created_at timestamptz not null default now()
);
create index ix_task_schedulable on repair_task(priority, created_at) where state = 'QUEUED';
