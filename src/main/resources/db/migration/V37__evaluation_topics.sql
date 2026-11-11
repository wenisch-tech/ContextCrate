-- Optional per-crate switches: let the judge rewrite each live question into a neutral topic and derive up to 5 tags.
-- Topic and tags are kept for the retention period even when raw questions are not logged.
alter table crate_evaluation_settings add column store_topic boolean not null default false;
alter table crate_evaluation_settings add column store_tags boolean not null default false;
alter table evaluation_case add column topic text;
alter table evaluation_case add column tags text;
