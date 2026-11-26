-- Graph RAG: a knowledge graph of entities and relations extracted from the chunks, with hierarchical communities and their summaries.
alter table crate_rag_settings add column graph_extraction_enabled boolean not null default false;
alter table crate_rag_settings add column graph_retrieval_enabled boolean not null default false;
alter table crate_rag_settings add column graph_search_mode varchar(10) not null default 'auto';

-- Per-chunk extraction cache (keyed by a fingerprint of chunk content and model); removed with its chunk.
create table graph_chunk_extraction (
    chunk_id uuid primary key,
    crate_id uuid not null,
    document_id uuid not null,
    fingerprint varchar(64) not null,
    model varchar(500),
    created_at timestamp with time zone not null,
    constraint fk_graph_extraction_chunk foreign key (chunk_id, crate_id)
        references document_chunk(id, crate_id) on delete cascade
);
create index idx_graph_extraction_crate on graph_chunk_extraction(crate_id);

create table graph_chunk_entity (
    id uuid primary key,
    crate_id uuid not null,
    chunk_id uuid not null,
    name varchar(300) not null,
    normalized_name varchar(300) not null,
    type varchar(60) not null,
    description text,
    constraint fk_graph_chunk_entity_chunk foreign key (chunk_id, crate_id)
        references document_chunk(id, crate_id) on delete cascade
);
create index idx_graph_chunk_entity_chunk on graph_chunk_entity(crate_id, chunk_id);

create table graph_chunk_relation (
    id uuid primary key,
    crate_id uuid not null,
    chunk_id uuid not null,
    source_name varchar(300) not null,
    target_name varchar(300) not null,
    description text,
    strength integer not null,
    constraint fk_graph_chunk_relation_chunk foreign key (chunk_id, crate_id)
        references document_chunk(id, crate_id) on delete cascade
);
create index idx_graph_chunk_relation_chunk on graph_chunk_relation(crate_id, chunk_id);

-- The built graph of a crate; replaced as a whole by every build.
create table graph_entity (
    id uuid primary key,
    crate_id uuid not null,
    name varchar(300) not null,
    normalized_name varchar(300) not null,
    type varchar(60) not null,
    description text,
    chunk_count integer not null,
    document_count integer not null,
    degree integer not null
);
create index idx_graph_entity_crate on graph_entity(crate_id, normalized_name);

create table graph_entity_chunk (
    entity_id uuid not null,
    chunk_id uuid not null,
    crate_id uuid not null,
    primary key (entity_id, chunk_id)
);
create index idx_graph_entity_chunk_crate on graph_entity_chunk(crate_id);

create table graph_edge (
    id uuid primary key,
    crate_id uuid not null,
    source_id uuid not null,
    target_id uuid not null,
    weight double precision not null,
    document_count integer not null,
    relation_count integer not null,
    description text
);
create index idx_graph_edge_crate on graph_edge(crate_id);

create table graph_community (
    id uuid primary key,
    crate_id uuid not null,
    level integer not null,
    parent_id uuid,
    title varchar(300) not null,
    summary text not null,
    rating double precision not null,
    size integer not null,
    fingerprint varchar(64) not null
);
create index idx_graph_community_crate on graph_community(crate_id, level);

create table graph_community_member (
    community_id uuid not null,
    entity_id uuid not null,
    crate_id uuid not null,
    primary key (community_id, entity_id)
);
create index idx_graph_community_member_crate on graph_community_member(crate_id);

create table graph_build (
    crate_id uuid primary key,
    status varchar(20) not null,
    dirty boolean not null,
    claimed_at timestamp with time zone,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    entities integer not null default 0,
    edges integer not null default 0,
    communities integer not null default 0,
    levels integer not null default 0,
    error text
);
