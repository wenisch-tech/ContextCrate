create table crawl_job (
    id uuid primary key,
    name varchar(200) not null,
    configuration_json text not null,
    enabled boolean not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);
create table crawl_run (
    id uuid primary key,
    job_id uuid not null references crawl_job(id),
    status varchar(32) not null,
    configuration_json text not null,
    started_at timestamp with time zone not null,
    finished_at timestamp with time zone
);
create table frontier_entry (
    id uuid primary key,
    run_id uuid not null references crawl_run(id),
    url varchar(4096) not null,
    canonical_url varchar(4096) not null,
    depth integer not null,
    status varchar(32) not null,
    discovered_at timestamp with time zone not null,
    unique(run_id, canonical_url)
);
create table pipeline_work_item (
    id uuid primary key,
    schema_version integer not null,
    stage varchar(32) not null,
    status varchar(32) not null,
    payload text not null,
    correlation_id uuid not null,
    idempotency_key varchar(500) not null,
    priority integer not null,
    attempts integer not null,
    available_at timestamp with time zone not null,
    lease_until timestamp with time zone,
    last_error text,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    unique(stage, idempotency_key)
);
create index idx_work_claim on pipeline_work_item(stage, status, available_at, priority);
create table fetch_record (
    id uuid primary key,
    run_id uuid not null references crawl_run(id),
    frontier_entry_id uuid not null references frontier_entry(id),
    requested_url varchar(4096) not null,
    final_url varchar(4096),
    status_code integer,
    content_type varchar(255),
    charset varchar(100),
    artifact_key varchar(1024),
    artifact_sha256 varchar(64),
    artifact_length bigint,
    duration_ms bigint,
    outcome varchar(32) not null,
    fetched_at timestamp with time zone not null,
    error_message text
);
create table normalized_document (
    id uuid primary key,
    run_id uuid not null references crawl_run(id),
    fetch_id uuid not null references fetch_record(id),
    canonical_url varchar(4096) not null,
    title varchar(1000),
    language varchar(50),
    description varchar(4000),
    author varchar(500),
    body text not null,
    content_hash varchar(64) not null,
    metadata_json text not null,
    indexed boolean not null,
    created_at timestamp with time zone not null,
    unique(run_id, canonical_url)
);
create table document_chunk (
    id uuid primary key,
    document_id uuid not null references normalized_document(id) on delete cascade,
    ordinal integer not null,
    heading varchar(1000),
    content text not null,
    content_hash varchar(64) not null,
    character_count integer not null,
    token_estimate integer not null,
    unique(document_id, ordinal)
);
create table app_user (
    id uuid primary key,
    email varchar(320) not null unique,
    password_hash varchar(100) not null,
    role varchar(32) not null,
    password_change_required boolean not null,
    enabled boolean not null,
    created_at timestamp with time zone not null
);
