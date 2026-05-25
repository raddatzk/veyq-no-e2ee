create table veyq.veyq_media_blob
(
    id                  bigint auto_increment
        primary key,
    tenant_id           bigint                                not null,
    content_sha256      varchar(64)                           not null,
    storage_key         varchar(512)                          not null,
    preview_storage_key varchar(512)                          null,
    mime_type           varchar(255)                          not null,
    size_bytes          bigint                                not null,
    captured_at         timestamp(6)                          null,
    camera_make         varchar(255)                          null,
    camera_model        varchar(255)                          null,
    lens_model          varchar(255)                          null,
    image_width         int                                   null,
    image_height        int                                   null,
    gps_latitude        double                                null,
    gps_longitude       double                                null,
    created_at          timestamp default current_timestamp() not null,
    updated_at          timestamp default current_timestamp() not null,
    constraint uq_veyq_media_blob_tenant_sha256
        unique (tenant_id, content_sha256),
    constraint fk_veyq_media_blob_tenant
        foreign key (tenant_id) references veyq.veyq_tenant (id)
            on delete cascade
);

create index idx_veyq_media_blob_tenant_id
    on veyq.veyq_media_blob (tenant_id);

alter table veyq.veyq_media
    add column media_blob_id bigint not null;

alter table veyq.veyq_media
    add constraint fk_veyq_media_blob
        foreign key (media_blob_id) references veyq.veyq_media_blob (id);

create index idx_veyq_media_blob_id
    on veyq.veyq_media (media_blob_id);

alter table veyq.veyq_media
    drop column mime_type;

alter table veyq.veyq_media
    drop column size_bytes;

alter table veyq.veyq_media
    drop column storage_key;

alter table veyq.veyq_media
    drop column camera_make;

alter table veyq.veyq_media
    drop column camera_model;

alter table veyq.veyq_media
    drop column lens_model;

alter table veyq.veyq_media
    drop column image_width;

alter table veyq.veyq_media
    drop column image_height;

alter table veyq.veyq_media
    drop column gps_latitude;

alter table veyq.veyq_media
    drop column gps_longitude;
