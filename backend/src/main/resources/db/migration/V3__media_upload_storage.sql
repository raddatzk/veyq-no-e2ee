alter table lunva_media
    add column mime_type varchar(255) not null default 'application/octet-stream';

alter table lunva_media
    add column size_bytes bigint not null default 0;

alter table lunva_media
    add column storage_key varchar(512) not null default '';

alter table lunva_media
    add column original_filename varchar(255) null;
