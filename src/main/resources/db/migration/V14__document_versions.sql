alter table normalized_document add column source_id uuid;
update normalized_document d set source_id = (
    select r.source_id from ingestion_run r where r.id = d.run_id
);

delete from normalized_document where id in (
    select id from (
        select id, row_number() over (
            partition by source_id, source_uri order by created_at desc, id desc
        ) as row_number
        from normalized_document
    ) duplicates where row_number > 1
);

alter table normalized_document add column identity_uri varchar(4096);
update normalized_document set identity_uri = source_uri;
alter table normalized_document add column version_number integer not null default 1;
alter table normalized_document add column current_version boolean not null default true;
alter table normalized_document alter column source_id set not null;
alter table normalized_document alter column identity_uri set not null;
alter table normalized_document add constraint fk_document_source
    foreign key (source_id) references source(id);
alter table normalized_document add constraint uq_document_source_identity_version
    unique (source_id, identity_uri, version_number);
create index idx_document_current on normalized_document(crate_id, current_version, created_at);
