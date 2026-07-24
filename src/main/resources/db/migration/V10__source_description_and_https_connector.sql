alter table source add column description varchar(2000);
update source set connector_type = 'HTTPS' where connector_type = 'WEB_CRAWLER';
