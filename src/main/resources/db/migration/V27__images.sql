alter table crate_rag_settings add column image_indexing_enabled boolean not null default true;
alter table crate_rag_settings add column image_description_enabled boolean not null default false;
alter table crate_rag_settings add column image_vision_model_id uuid;
alter table crate_rag_settings add column image_max_per_run integer not null default 200;
alter table crate_rag_settings add column image_max_bytes integer not null default 5242880;
alter table crate_rag_settings add column image_min_size integer not null default 64;
alter table crate_rag_settings add column image_skip_patterns varchar(500) not null default '';
alter table system_setting add column allow_vision_descriptions boolean not null default true;
alter table document_chunk add column media_url varchar(2000);

create table document_image (
    id uuid primary key,
    crate_id uuid not null,
    document_id uuid not null,
    run_id uuid not null,
    url varchar(2000) not null,
    view_url varchar(2000),
    alt varchar(1000),
    caption varchar(1000),
    context varchar(1000),
    heading varchar(1000),
    status varchar(16) not null,
    skip_reason varchar(120),
    artifact_key varchar(500),
    content_hash varchar(64),
    description text,
    model varchar(500),
    created_at timestamp with time zone not null,
    described_at timestamp with time zone
);
create index idx_document_image_document on document_image(document_id);
create index idx_document_image_crate_hash on document_image(crate_id, content_hash);
