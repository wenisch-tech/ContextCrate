alter table crate_provider_settings
    add column reranking_enabled boolean;
alter table crate_provider_settings
    add column reranking_provider varchar(40);
alter table crate_provider_settings
    add column reranking_candidate_limit integer;
alter table crate_provider_settings
    add column reranking_local_model_id varchar(500);
alter table crate_provider_settings
    add column reranking_local_revision varchar(200);
alter table crate_provider_settings
    add column reranking_local_download_url varchar(2000);
alter table crate_provider_settings
    add column reranking_local_cache_path varchar(1000);
alter table crate_provider_settings
    add column reranking_local_model_path varchar(1000);
alter table crate_provider_settings
    add column reranking_cohere_base_url varchar(2000);
alter table crate_provider_settings
    add column reranking_cohere_model varchar(500);
alter table crate_provider_settings
    add column reranking_cohere_api_key varchar(2000);
alter table crate_provider_settings
    add column reranking_cohere_max_input_characters integer;
alter table crate_provider_settings
    add column reranking_cohere_timeout_seconds integer;
