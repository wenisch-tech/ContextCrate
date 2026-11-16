-- Captions: the answer model describes tables and diagram code; the caption is indexed next to the chunk and points back at it.
alter table crate_rag_settings add column captions_enabled boolean not null default false;

-- Cache of the generated captions (keyed by a fingerprint of chunk content, kind and model) so rebuilds do not call the model again.
create table chunk_caption (
    id uuid primary key,
    crate_id uuid not null,
    chunk_id uuid not null,
    kind varchar(16) not null,
    caption text not null,
    fingerprint varchar(64) not null,
    model varchar(500),
    created_at timestamp with time zone not null,
    constraint fk_chunk_caption_chunk foreign key (chunk_id, crate_id)
        references document_chunk(id, crate_id) on delete cascade,
    constraint uq_chunk_caption_chunk unique (chunk_id)
);
create index idx_chunk_caption_crate on chunk_caption(crate_id);
