alter table system_setting add column onboarding_policy varchar(32) not null default 'DO_NOTHING';
alter table system_setting add column onboarding_crate_id uuid;
alter table app_user add column onboarding_crate_creation_required boolean not null default false;
