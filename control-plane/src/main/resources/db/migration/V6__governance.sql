create table node_certificate (
  node_id uuid not null references repair_node(id),
  serial varchar(128) not null,
  public_key_sha256 char(64) not null,
  status varchar(16) not null,
  not_before timestamptz not null,
  not_after timestamptz not null,
  created_at timestamptz not null default now(),
  revoked_at timestamptz null,
  primary key (node_id, serial)
);

create index ix_node_certificate_status on node_certificate(status);

insert into node_certificate (
  node_id, serial, public_key_sha256, status, not_before, not_after, created_at)
select
  id,
  certificate_serial,
  public_key_sha256,
  'ACTIVE',
  created_at,
  created_at + interval '365 days',
  created_at
from repair_node;
