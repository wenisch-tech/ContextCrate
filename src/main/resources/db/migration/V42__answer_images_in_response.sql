-- The answer model may place a retrieved picture in its answer; it is returned to the client as a base64 Markdown image.
alter table crate_rag_settings add column include_images_in_answer boolean not null default true;
