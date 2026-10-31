-- Per-crate retrieval counters used for the "most requested documents / sources" metrics.
-- chunk_id is the nil UUID for document-level hits (fetch_document).
create table content_hit (
  crate_id uuid not null,
  document_id uuid not null,
  chunk_id uuid not null,
  source_id uuid,
  hits bigint not null,
  last_hit_at timestamp not null,
  primary key (crate_id, document_id, chunk_id)
);
create index idx_content_hit_crate_hits on content_hit (crate_id, hits);
