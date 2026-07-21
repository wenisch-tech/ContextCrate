insert into provider_settings (id)
select 1
where not exists (select 1 from provider_settings where id = 1);
