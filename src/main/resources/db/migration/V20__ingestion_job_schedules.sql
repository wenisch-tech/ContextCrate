alter table ingestion_job add column mode varchar(16) not null default 'MANUAL';
alter table ingestion_job add column cron_expression varchar(120);
alter table system_setting add column time_zone varchar(64) not null default 'UTC';
