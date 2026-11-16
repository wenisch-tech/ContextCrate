-- The caption chunk of an image is stored after the text of its document. anchor_ordinal is the ordinal of the text chunk
-- that contains the image, so the context window and hierarchical sections can use the exact position of the image.
alter table document_chunk add column anchor_ordinal integer;
create index idx_document_chunk_anchor on document_chunk(document_id, anchor_ordinal);
