alter table crate_rag_settings add column query_transformation_enabled boolean not null default false;
alter table crate_rag_settings add column query_transformation_strategies varchar(60) not null default 'auto';
