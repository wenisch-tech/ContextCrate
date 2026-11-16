alter table crate_rag_settings add column dartboard_enabled boolean not null default false;
alter table crate_rag_settings add column dartboard_oversampling integer not null default 3;
alter table crate_rag_settings add column dartboard_relevance_weight double precision not null default 1.0;
alter table crate_rag_settings add column dartboard_diversity_weight double precision not null default 1.0;
alter table crate_rag_settings add column dartboard_sigma double precision not null default 0.1;
