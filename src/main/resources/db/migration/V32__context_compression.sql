-- Contextual compression: after retrieval a model keeps only the sentences of each chunk that matter for the question.
alter table crate_rag_settings add column context_compression_enabled boolean not null default false;
