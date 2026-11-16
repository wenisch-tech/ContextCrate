alter table crate_rag_settings add column hierarchical_enabled boolean not null default false;
alter table crate_rag_settings add column hierarchical_top_k integer not null default 4;

-- One row per summarized document section. The row is also the register of the section's exact position:
-- the document, its heading path and the ordinals of its first and last chunk (and, for code, the lines).
create table section_summary (
    id uuid primary key,
    crate_id uuid not null,
    document_id uuid not null,
    heading varchar(1000),
    first_ordinal integer not null,
    last_ordinal integer not null,
    start_line integer,
    end_line integer,
    fingerprint varchar(64) not null,
    model varchar(500),
    summary text not null,
    created_at timestamp with time zone not null,
    constraint fk_section_summary_document foreign key (document_id, crate_id)
        references normalized_document(id, crate_id) on delete cascade
);
create index idx_section_summary_document on section_summary(document_id);
create index idx_section_summary_crate on section_summary(crate_id);
