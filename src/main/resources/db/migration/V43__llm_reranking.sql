alter table crate_rag_settings add column llm_rerank_enabled boolean not null default false;
alter table crate_rag_settings add column llm_rerank_top_n integer not null default 4;
