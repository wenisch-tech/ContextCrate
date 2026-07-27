alter table crate_provider_settings
    add column embedding_openai_automatic_limit_recovery boolean;
alter table crate_provider_settings
    add column embedding_openai_learned_max_input_characters integer;
alter table crate_provider_settings
    add column embedding_openai_learned_limit_base_url varchar(2000);
alter table crate_provider_settings
    add column embedding_openai_learned_limit_model varchar(500);
