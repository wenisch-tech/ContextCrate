-- Number of leading characters of a chunk that repeat the end of the previous chunk of the same
-- document (prose cut with overlap). NULL = chunk created before this column existed (unknown).
alter table document_chunk add column overlap_chars integer;
-- Chunks before and after a matched chunk that are added to the answer context (0 = off).
alter table crate_rag_settings add column context_window integer not null default 0;
