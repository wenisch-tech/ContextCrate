alter table shared_model add column default_for_new_crates boolean not null default false;
alter table system_setting add column allow_custom_endpoints boolean not null default true;
alter table system_setting add column allow_local_models boolean not null default true;
