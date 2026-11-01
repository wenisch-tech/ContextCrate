alter table document_chunk add column kind varchar(16);
alter table document_chunk add column language varchar(32);
alter table document_chunk add column symbol varchar(512);
alter table document_chunk add column start_line integer;
alter table document_chunk add column end_line integer;
