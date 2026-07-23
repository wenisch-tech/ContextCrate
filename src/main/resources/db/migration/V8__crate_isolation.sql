create table crate (
    id uuid primary key,
    name varchar(200) not null,
    description varchar(2000),
    status varchar(32) not null,
    created_by uuid references app_user(id),
    active_index_generation integer not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table crate_member (
    crate_id uuid not null references crate(id) on delete cascade,
    user_id uuid not null references app_user(id) on delete cascade,
    role varchar(16) not null,
    invited_by uuid references app_user(id),
    created_at timestamp with time zone not null,
    primary key (crate_id, user_id)
);
create index idx_crate_member_user on crate_member(user_id);

create table system_setting (
    id integer primary key,
    crate_creation_mode varchar(32) not null
);
insert into system_setting(id, crate_creation_mode) values (1, 'EVERYONE');

alter table app_user add column can_create_crates boolean default false not null;

create table admin_elevation (
    id uuid primary key,
    admin_user_id uuid not null references app_user(id),
    crate_id uuid not null references crate(id) on delete cascade,
    reason varchar(1000) not null,
    started_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    ended_at timestamp with time zone
);
create index idx_admin_elevation_active
    on admin_elevation(admin_user_id, crate_id, expires_at);

create table crate_index_generation (
    crate_id uuid not null references crate(id) on delete cascade,
    generation integer not null,
    status varchar(32) not null,
    configuration_fingerprint varchar(128) not null,
    model varchar(500),
    dimensions integer,
    document_count bigint not null,
    error_message text,
    created_at timestamp with time zone not null,
    activated_at timestamp with time zone,
    primary key (crate_id, generation)
);

create table crate_rag_settings (
    crate_id uuid primary key references crate(id) on delete cascade,
    strict_grounding boolean not null,
    allow_client_history boolean not null,
    inline_citations boolean not null,
    structured_sources boolean not null,
    retrieval_mode varchar(20) not null,
    source_limit integer not null
);

create table crate_provider_settings (
    crate_id uuid primary key references crate(id) on delete cascade,
    embeddings_enabled boolean,
    embeddings_provider varchar(40),
    embedding_local_model_id varchar(500),
    embedding_local_revision varchar(200),
    embedding_local_download_url varchar(2000),
    embedding_local_cache_path varchar(1000),
    embedding_local_model_path varchar(1000),
    embedding_openai_base_url varchar(2000),
    embedding_openai_model varchar(500),
    embedding_openai_api_key varchar(2000),
    embedding_openai_dimensions integer,
    answering_enabled boolean,
    answering_base_url varchar(2000),
    answering_model varchar(500),
    answering_api_key varchar(2000)
);

insert into crate(
    id, name, description, status, created_by, active_index_generation, created_at, updated_at)
values (
    '00000000-0000-0000-0000-000000000001',
    'Legacy',
    'Content migrated from the pre-crate installation',
    'ACTIVE',
    null,
    1,
    current_timestamp,
    current_timestamp
);

insert into crate_member(crate_id, user_id, role, invited_by, created_at)
select
    '00000000-0000-0000-0000-000000000001',
    id,
    'OWNER',
    null,
    current_timestamp
from app_user
where role = 'ADMIN';

insert into crate_rag_settings(
    crate_id, strict_grounding, allow_client_history, inline_citations,
    structured_sources, retrieval_mode, source_limit)
select
    '00000000-0000-0000-0000-000000000001',
    strict_grounding, allow_client_history, inline_citations,
    structured_sources, retrieval_mode, source_limit
from rag_settings
where id = 1;

insert into crate_provider_settings(
    crate_id, embeddings_enabled, embeddings_provider, embedding_local_model_id,
    embedding_local_revision, embedding_local_download_url, embedding_local_cache_path,
    embedding_local_model_path, embedding_openai_base_url, embedding_openai_model,
    embedding_openai_api_key, embedding_openai_dimensions, answering_enabled,
    answering_base_url, answering_model, answering_api_key)
select
    '00000000-0000-0000-0000-000000000001',
    embeddings_enabled, embeddings_provider, embedding_local_model_id,
    embedding_local_revision, embedding_local_download_url, embedding_local_cache_path,
    embedding_local_model_path, embedding_openai_base_url, embedding_openai_model,
    embedding_openai_api_key, embedding_openai_dimensions, answering_enabled,
    answering_base_url, answering_model, answering_api_key
from provider_settings
where id = 1;

insert into crate_index_generation(
    crate_id, generation, status, configuration_fingerprint, model, dimensions,
    document_count, created_at, activated_at)
values (
    '00000000-0000-0000-0000-000000000001',
    1,
    'ACTIVE',
    'legacy',
    null,
    null,
    0,
    current_timestamp,
    current_timestamp
);

alter table crawl_job add column crate_id uuid;
alter table crawl_run add column crate_id uuid;
alter table frontier_entry add column crate_id uuid;
alter table fetch_record add column crate_id uuid;
alter table normalized_document add column crate_id uuid;
alter table document_chunk add column crate_id uuid;
alter table pipeline_work_item add column crate_id uuid;
alter table extraction_rule add column crate_id uuid;
alter table extraction_result add column crate_id uuid;
alter table audit_log add column crate_id uuid;

update crawl_job set crate_id = '00000000-0000-0000-0000-000000000001';
update crawl_run set crate_id = '00000000-0000-0000-0000-000000000001';
update frontier_entry set crate_id = '00000000-0000-0000-0000-000000000001';
update fetch_record set crate_id = '00000000-0000-0000-0000-000000000001';
update normalized_document set crate_id = '00000000-0000-0000-0000-000000000001';
update document_chunk set crate_id = '00000000-0000-0000-0000-000000000001';
update pipeline_work_item set crate_id = '00000000-0000-0000-0000-000000000001';
update fetch_record
set artifact_key = 'crates/00000000-0000-0000-0000-000000000001/' || artifact_key
where artifact_key is not null and artifact_key not like 'crates/%';
update extraction_rule set crate_id = '00000000-0000-0000-0000-000000000001';
update extraction_result set crate_id = '00000000-0000-0000-0000-000000000001';
update audit_log set crate_id = '00000000-0000-0000-0000-000000000001';

alter table crawl_job alter column crate_id set not null;
alter table crawl_run alter column crate_id set not null;
alter table frontier_entry alter column crate_id set not null;
alter table fetch_record alter column crate_id set not null;
alter table normalized_document alter column crate_id set not null;
alter table document_chunk alter column crate_id set not null;
alter table pipeline_work_item alter column crate_id set not null;
alter table extraction_rule alter column crate_id set not null;
alter table extraction_result alter column crate_id set not null;
alter table audit_log alter column crate_id set not null;

alter table crawl_job add constraint fk_crawl_job_crate foreign key (crate_id) references crate(id);
alter table crawl_run add constraint fk_crawl_run_crate foreign key (crate_id) references crate(id);
alter table frontier_entry add constraint fk_frontier_crate foreign key (crate_id) references crate(id);
alter table fetch_record add constraint fk_fetch_crate foreign key (crate_id) references crate(id);
alter table normalized_document add constraint fk_document_crate foreign key (crate_id) references crate(id);
alter table document_chunk add constraint fk_chunk_crate foreign key (crate_id) references crate(id);
alter table pipeline_work_item add constraint fk_work_crate foreign key (crate_id) references crate(id);
alter table extraction_rule add constraint fk_rule_crate foreign key (crate_id) references crate(id);
alter table extraction_result add constraint fk_result_crate foreign key (crate_id) references crate(id);
alter table audit_log add constraint fk_audit_crate foreign key (crate_id) references crate(id);

alter table crawl_job add constraint uq_crawl_job_id_crate unique (id, crate_id);
alter table crawl_run add constraint uq_crawl_run_id_crate unique (id, crate_id);
alter table frontier_entry add constraint uq_frontier_id_crate unique (id, crate_id);
alter table fetch_record add constraint uq_fetch_id_crate unique (id, crate_id);
alter table normalized_document add constraint uq_document_id_crate unique (id, crate_id);
alter table document_chunk add constraint uq_chunk_id_crate unique (id, crate_id);
alter table extraction_rule add constraint uq_rule_id_crate unique (id, crate_id);

alter table crawl_run add constraint fk_run_job_crate
    foreign key (job_id, crate_id) references crawl_job(id, crate_id);
alter table frontier_entry add constraint fk_frontier_run_crate
    foreign key (run_id, crate_id) references crawl_run(id, crate_id);
alter table fetch_record add constraint fk_fetch_run_crate
    foreign key (run_id, crate_id) references crawl_run(id, crate_id);
alter table fetch_record add constraint fk_fetch_frontier_crate
    foreign key (frontier_entry_id, crate_id) references frontier_entry(id, crate_id);
alter table normalized_document add constraint fk_document_run_crate
    foreign key (run_id, crate_id) references crawl_run(id, crate_id);
alter table normalized_document add constraint fk_document_fetch_crate
    foreign key (fetch_id, crate_id) references fetch_record(id, crate_id);
alter table document_chunk add constraint fk_chunk_document_crate
    foreign key (document_id, crate_id) references normalized_document(id, crate_id);
alter table extraction_result add constraint fk_result_rule_crate
    foreign key (rule_id, crate_id) references extraction_rule(id, crate_id);
alter table extraction_result add constraint fk_result_run_crate
    foreign key (run_id, crate_id) references crawl_run(id, crate_id);
alter table extraction_result add constraint fk_result_document_crate
    foreign key (document_id, crate_id) references normalized_document(id, crate_id);
alter table extraction_result add constraint fk_result_chunk_crate
    foreign key (chunk_id, crate_id) references document_chunk(id, crate_id);

create index idx_crawl_job_crate on crawl_job(crate_id);
create index idx_crawl_run_crate on crawl_run(crate_id, started_at);
create index idx_document_crate on normalized_document(crate_id, created_at);
create index idx_work_crate on pipeline_work_item(crate_id, stage, status);
create index idx_rule_crate on extraction_rule(crate_id, created_at);
create index idx_result_crate on extraction_result(crate_id, extracted_at);
create index idx_audit_crate on audit_log(crate_id, created_at);

alter table api_key add column key_type varchar(16) default 'CRATE' not null;
alter table api_key add column user_id uuid references app_user(id);
alter table api_key add column crate_id uuid references crate(id);
alter table api_key add column crate_role varchar(16);
update api_key
set crate_id = '00000000-0000-0000-0000-000000000001',
    crate_role = 'EDITOR';
