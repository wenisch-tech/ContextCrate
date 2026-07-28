alter table normalized_document add column indexed_at timestamp with time zone;
alter table normalized_document add column indexed_at_estimated boolean not null default false;

update normalized_document
set indexed_at = created_at, indexed_at_estimated = true
where indexed = true;
