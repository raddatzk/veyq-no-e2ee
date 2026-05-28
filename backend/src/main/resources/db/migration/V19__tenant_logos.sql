alter table veyq.veyq_tenant
    add column logo_storage_key varchar(512);

alter table veyq.veyq_tenant
    add column logo_content_type varchar(64);

alter table veyq.veyq_tenant
    add column logo_updated_at timestamp;
