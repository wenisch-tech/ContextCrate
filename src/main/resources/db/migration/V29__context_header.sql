alter table crate_rag_settings add column context_header_variant varchar(30) not null default 'off';
alter table crate_rag_settings add column context_header_words integer not null default 1000;

create table document_context (
    id uuid primary key,
    crate_id uuid not null,
    document_id uuid not null,
    content_hash varchar(64) not null,
    fingerprint varchar(64) not null,
    model varchar(500),
    title varchar(500) not null,
    summary text,
    created_at timestamp with time zone not null,
    constraint fk_document_context_document foreign key (document_id, crate_id)
        references normalized_document(id, crate_id) on delete cascade,
    constraint uq_document_context_document unique (document_id)
);
create index idx_document_context_crate on document_context(crate_id);
