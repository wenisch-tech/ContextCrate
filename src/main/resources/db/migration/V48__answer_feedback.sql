create table answer_feedback (
    id uuid primary key,
    crate_id uuid not null references crate(id) on delete cascade,
    channel varchar(10) not null,
    actor varchar(255),
    created_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    token_hash varchar(64) not null,
    question text not null,
    answer text not null,
    relevance smallint,
    quality smallint,
    comment text,
    rated_at timestamp with time zone,
    enrichment_status varchar(12) not null default 'NONE',
    enriched_document_id uuid
);
create unique index idx_answer_feedback_token on answer_feedback(token_hash);
create index idx_answer_feedback_rated on answer_feedback(crate_id, rated_at);
create index idx_answer_feedback_expiry on answer_feedback(expires_at);

create table answer_feedback_source (
    id uuid primary key,
    feedback_id uuid not null references answer_feedback(id) on delete cascade,
    crate_id uuid not null,
    citation integer not null,
    chunk_id uuid,
    document_id uuid,
    identity_uri varchar(4096),
    title varchar(1000),
    source_uri varchar(4096)
);
create index idx_answer_feedback_source_fb on answer_feedback_source(feedback_id);
create index idx_answer_feedback_source_doc on answer_feedback_source(crate_id, document_id);

alter table crate_rag_settings add column feedback_enabled boolean not null default false;
alter table crate_rag_settings add column feedback_link_ttl_hours integer not null default 168;
alter table crate_rag_settings add column feedback_retrieval_enabled boolean not null default false;
alter table crate_rag_settings add column feedback_retrieval_max_per_document integer not null default 10;
alter table crate_rag_settings add column feedback_enrichment_enabled boolean not null default false;
alter table crate_rag_settings add column feedback_enrichment_interval_hours integer not null default 24;
alter table crate_rag_settings add column feedback_enrichment_min_quality integer not null default 4;
alter table crate_rag_settings add column feedback_enrichment_min_relevance integer not null default 3;
alter table crate_rag_settings add column feedback_enrichment_requires_approval boolean not null default true;
alter table crate_rag_settings add column feedback_enrichment_last_run timestamp with time zone;
