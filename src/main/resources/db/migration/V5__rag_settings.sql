create table rag_settings (
  id integer primary key,
  strict_grounding boolean not null,
  allow_client_history boolean not null,
  inline_citations boolean not null,
  structured_sources boolean not null,
  retrieval_mode varchar(20) not null,
  source_limit integer not null
);
