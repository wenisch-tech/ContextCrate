-- Retrieval mode 'fusion': keyword and vector scores are min-max normalized and combined with this weight of the vector leg.
alter table crate_rag_settings add column fusion_vector_weight double precision not null default 0.5;
