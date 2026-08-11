alter table crate_rag_settings add column answer_verification_enabled boolean not null default true;
alter table crate_rag_settings add column answer_verification_failure_action varchar(20) not null default 'revise-once';
