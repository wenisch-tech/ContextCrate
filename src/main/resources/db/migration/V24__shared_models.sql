create table shared_model (
    id uuid primary key,
    name varchar(120) not null,
    kind varchar(16) not null,
    base_url varchar(2000) not null,
    model varchar(500) not null,
    api_key varchar(2000),
    dimensions integer,
    max_input_characters integer,
    timeout_seconds integer,
    available_to_users boolean not null default false,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uq_shared_model_kind_name unique (kind, name)
);
alter table crate_provider_settings add column embedding_shared_model_id uuid;
alter table crate_provider_settings add column reranking_shared_model_id uuid;
alter table crate_provider_settings add column answering_shared_model_id uuid;
