-- Questions the knowledge base could not answer: classified per live case, and switched on per crate.
-- answer_status is ANSWERED, PARTIAL or UNANSWERED; gap_reason is no-sources, not-in-sources or partial.
alter table evaluation_case add column answer_status varchar(12);
alter table evaluation_case add column gap_reason varchar(16);
alter table crate_evaluation_settings add column track_unanswered boolean not null default false;
