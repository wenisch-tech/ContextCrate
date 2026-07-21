create table provider_settings (
  id integer primary key,
  embeddings_enabled boolean,
  embeddings_provider varchar(40),
  embedding_local_model_id varchar(500),
  embedding_local_revision varchar(200),
  embedding_local_download_url varchar(2000),
  embedding_local_cache_path varchar(1000),
  embedding_local_model_path varchar(1000),
  embedding_openai_base_url varchar(2000),
  embedding_openai_model varchar(500),
  embedding_openai_api_key varchar(2000),
  embedding_openai_dimensions integer,
  answering_enabled boolean,
  answering_base_url varchar(2000),
  answering_model varchar(500),
  answering_api_key varchar(2000)
);
