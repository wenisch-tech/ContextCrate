-- Document enrichment: an LLM writes questions that each chunk answers; they are indexed next to the chunk.
alter table crate_rag_settings add column enrichment_enabled boolean not null default false;
alter table crate_rag_settings add column enrichment_questions integer not null default 5;

-- Cache of the generated questions (keyed by a fingerprint of chunk content, model and count) so rebuilds do not call the model again.
create table chunk_question (
    id uuid primary key,
    crate_id uuid not null,
    chunk_id uuid not null,
    ordinal integer not null,
    question text not null,
    fingerprint varchar(64) not null,
    created_at timestamp with time zone not null,
    constraint fk_chunk_question_chunk foreign key (chunk_id, crate_id)
        references document_chunk(id, crate_id) on delete cascade
);
create index idx_chunk_question_chunk on chunk_question(crate_id, chunk_id, ordinal);
