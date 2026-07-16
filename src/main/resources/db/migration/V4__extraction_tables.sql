create table extraction_rule (
    id uuid primary key,
    name varchar(200) not null,
    type varchar(32) not null,
    pattern text,
    enabled boolean not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);
create index idx_extraction_rule_enabled on extraction_rule(enabled);

create table extraction_result (
    id uuid primary key,
    rule_id uuid not null references extraction_rule(id),
    run_id uuid not null references crawl_run(id),
    document_id uuid not null references normalized_document(id) on delete cascade,
    chunk_id uuid not null references document_chunk(id) on delete cascade,
    chunk_ordinal integer not null,
    matched_value varchar(4096) not null,
    match_start integer not null,
    match_end integer not null,
    context_before text,
    context_after text,
    extracted_at timestamp with time zone not null,
    unique(rule_id, chunk_id, match_start, match_end, matched_value)
);
create index idx_extraction_result_run_rule on extraction_result(run_id, rule_id);
create index idx_extraction_result_document on extraction_result(document_id);
create index idx_extraction_result_chunk on extraction_result(chunk_id);
create index idx_extraction_result_value on extraction_result(rule_id, matched_value);
create index idx_extraction_result_extracted on extraction_result(extracted_at);