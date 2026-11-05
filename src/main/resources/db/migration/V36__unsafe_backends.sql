-- Administrators decide whether crate creators may skip TLS certificate validation for their own backends.
alter table system_setting add column allow_unsafe_backends boolean not null default false;
alter table crate_backend_settings add column s3_trust_all boolean not null default false;
