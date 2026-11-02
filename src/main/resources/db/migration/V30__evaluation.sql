create table crate_evaluation_settings (
    crate_id uuid primary key references crate(id) on delete cascade,
    enabled boolean not null default false,
    sampling_rate double precision not null default 1.0,
    top_k integer not null default 5,
    pool_size integer not null default 20,
    context_enabled boolean not null default true,
    faithfulness_enabled boolean not null default true,
    answer_relevance_enabled boolean not null default true,
    completeness_enabled boolean not null default true,
    log_questions boolean not null default false,
    retention_days integer not null default 30,
    judge_shared_model_id uuid,
    judge_base_url varchar(2000),
    judge_model varchar(500),
    judge_api_key varchar(2000),
    judge_timeout_seconds integer not null default 120
);

create table evaluation_golden_item (
    id uuid primary key,
    crate_id uuid not null references crate(id) on delete cascade,
    question text not null,
    reference_answer text,
    relevant_uris text,
    created_at timestamp with time zone not null
);
create index idx_evaluation_golden_crate on evaluation_golden_item(crate_id, created_at);

create table evaluation_case (
    id uuid primary key,
    crate_id uuid not null references crate(id) on delete cascade,
    source varchar(10) not null,
    status varchar(10) not null,
    golden_item_id uuid,
    created_at timestamp with time zone not null,
    evaluated_at timestamp with time zone,
    question text,
    answer text,
    snapshot_json text,
    details_json text,
    k integer,
    precision_at_k double precision,
    recall_at_k double precision,
    mrr double precision,
    average_precision double precision,
    ndcg double precision,
    faithfulness double precision,
    statements_total integer,
    statements_supported integer,
    answer_relevance double precision,
    completeness double precision,
    reference_recall double precision,
    judge_model varchar(500),
    error text
);
create index idx_evaluation_case_crate_time on evaluation_case(crate_id, created_at);
create index idx_evaluation_case_status on evaluation_case(status, created_at);
