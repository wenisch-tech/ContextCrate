create table source (
    id uuid primary key,
    crate_id uuid not null,
    name varchar(200) not null,
    connector_type varchar(32) not null,
    configuration_json text not null,
    enabled boolean not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_source_crate foreign key (crate_id) references crate(id),
    constraint uq_source_id_crate unique (id, crate_id)
);

insert into source (id, crate_id, name, connector_type, configuration_json, enabled, created_at, updated_at)
select id, crate_id, name, 'WEB_CRAWLER', configuration_json, enabled, created_at, updated_at
from crawl_job;

alter table crawl_job rename to ingestion_job;
alter table ingestion_job add column source_id uuid;
update ingestion_job set source_id = id;
alter table ingestion_job alter column source_id set not null;
alter table ingestion_job add constraint fk_ingestion_job_source
    foreign key (source_id, crate_id) references source(id, crate_id);

alter table crawl_run rename to ingestion_run;
alter table ingestion_run rename column job_id to ingestion_job_id;
alter table ingestion_run rename column configuration_json to job_configuration_json;
alter table ingestion_run add column source_id uuid;
alter table ingestion_run add column source_configuration_json text;
alter table ingestion_run add column resolved_revision varchar(255);
update ingestion_run set source_id = ingestion_job_id;
update ingestion_run set source_configuration_json = job_configuration_json;
alter table ingestion_run alter column source_id set not null;
alter table ingestion_run alter column source_configuration_json set not null;
alter table ingestion_run add constraint fk_ingestion_run_source
    foreign key (source_id, crate_id) references source(id, crate_id);

alter table frontier_entry rename to source_item;
alter table source_item rename column url to locator;
alter table source_item rename column canonical_url to source_uri;

alter table fetch_record rename to acquisition_record;
alter table acquisition_record rename column frontier_entry_id to source_item_id;
alter table acquisition_record rename column requested_url to requested_locator;
alter table acquisition_record rename column final_url to final_locator;

alter table normalized_document rename column fetch_id to acquisition_id;
alter table normalized_document rename column canonical_url to source_uri;

create index idx_source_crate on source(crate_id);
create index idx_ingestion_job_source on ingestion_job(source_id);
create index idx_ingestion_run_source on ingestion_run(source_id, started_at);

update pipeline_work_item set stage = 'WEB_FETCH' where stage = 'FETCH';
