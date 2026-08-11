alter table crate_rag_settings add column retrieval_strategy varchar(20) not null default 'standard';
alter table crate_rag_settings add column proposition_failure_policy varchar(30) not null default 'fail-indexing';

create table proposition_evaluation (
    id uuid primary key,
    crate_id uuid not null,
    chunk_id uuid not null,
    content_hash varchar(64) not null,
    fingerprint varchar(64) not null,
    model varchar(500),
    status varchar(20) not null,
    error_message text,
    created_at timestamp with time zone not null,
    constraint fk_proposition_evaluation_chunk foreign key (chunk_id, crate_id)
        references document_chunk(id, crate_id) on delete cascade,
    constraint uq_proposition_evaluation_chunk unique (chunk_id)
);
create index idx_proposition_evaluation_crate on proposition_evaluation(crate_id, created_at);

create table chunk_proposition (
    id uuid primary key,
    evaluation_id uuid not null references proposition_evaluation(id) on delete cascade,
    crate_id uuid not null,
    chunk_id uuid not null,
    ordinal integer not null,
    proposition text not null,
    fidelity_score integer not null,
    context_score integer not null,
    completeness_score integer not null,
    focus_score integer not null,
    accepted boolean not null,
    constraint fk_chunk_proposition_chunk foreign key (chunk_id, crate_id)
        references document_chunk(id, crate_id) on delete cascade,
    constraint uq_chunk_proposition_ordinal unique (evaluation_id, ordinal)
);
create index idx_chunk_proposition_chunk on chunk_proposition(crate_id, chunk_id, ordinal);
