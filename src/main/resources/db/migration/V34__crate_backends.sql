-- Administrators decide whether crate creators may choose a dedicated storage and/or search backend.
alter table system_setting add column allow_custom_storage boolean not null default false;
alter table system_setting add column allow_custom_search_backend boolean not null default false;

-- Dedicated storage / search backend of a crate. A row exists only for crates that do not use the global providers.
create table crate_backend_settings (
    crate_id uuid primary key references crate(id) on delete cascade,
    storage_mode varchar(16) not null default 'GLOBAL',
    s3_endpoint varchar(1000),
    s3_region varchar(100),
    s3_bucket varchar(255),
    s3_prefix varchar(500),
    s3_access_key varchar(500),
    s3_secret_key varchar(2000),
    s3_path_style boolean not null default true,
    index_mode varchar(16) not null default 'GLOBAL',
    index_backend varchar(32),
    index_endpoint varchar(1000),
    index_api_key varchar(2000),
    index_username varchar(500),
    index_password varchar(2000),
    index_prefix varchar(100),
    index_trust_all boolean not null default false,
    created_at timestamp with time zone not null
);
