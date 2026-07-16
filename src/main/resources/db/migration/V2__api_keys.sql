create table api_key (
    id uuid primary key,
    name varchar(200) not null,
    key_prefix varchar(16) not null,
    key_hash varchar(64) not null unique,
    created_at timestamp with time zone not null,
    revoked boolean not null
);
