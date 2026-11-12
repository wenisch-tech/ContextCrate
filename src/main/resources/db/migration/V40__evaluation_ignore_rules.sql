-- Ignore rules: questions, topics or tags (for example monitoring health checks) that are left out of the evaluation list and report.
create table evaluation_ignore_rule (
  id uuid primary key,
  crate_id uuid not null,
  kind varchar(10) not null,
  pattern varchar(2000) not null,
  contains_match boolean not null default false,
  created_by varchar(320),
  created_at timestamp not null
);
create index idx_evaluation_ignore_rule_crate on evaluation_ignore_rule (crate_id);
-- The rule that caught a case; null for cases that count normally.
alter table evaluation_case add column ignored_rule_id uuid;
create index idx_evaluation_case_ignored on evaluation_case (crate_id, ignored_rule_id);
