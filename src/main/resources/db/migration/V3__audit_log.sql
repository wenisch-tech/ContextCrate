create table audit_log (
    id uuid primary key,
    actor varchar(320) not null,
    action varchar(100) not null,
    subject varchar(500) not null,
    details text not null,
    created_at timestamp with time zone not null
);
create index idx_audit_created on audit_log(created_at);
