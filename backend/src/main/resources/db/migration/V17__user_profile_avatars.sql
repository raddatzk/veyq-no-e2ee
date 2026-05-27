alter table veyq.veyq_user
    add column avatar_storage_key varchar(512);

alter table veyq.veyq_user
    add column avatar_content_type varchar(64);

alter table veyq.veyq_user
    add column avatar_updated_at timestamp;
