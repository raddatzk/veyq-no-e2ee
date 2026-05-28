create table veyq.veyq_memory_media_bucket
(
    id                bigint auto_increment
        primary key,
    public_id         varchar(16)                           not null,
    tenant_id         bigint                                not null,
    memory_id         bigint                                not null,
    parent_bucket_id  bigint                                null,
    name              varchar(120)                          not null,
    display_name      varchar(255)                          not null,
    primary_bucket    tinyint(1) default 0                  not null,
    uploader_user_id  bigint                                null,
    share_identity_id bigint                                null,
    created_at        timestamp default current_timestamp() not null,
    updated_at        timestamp default current_timestamp() not null,
    constraint uq_veyq_memory_media_bucket_public_id
        unique (public_id),
    constraint uq_veyq_memory_media_bucket_parent_name
        unique (memory_id, parent_bucket_id, name),
    constraint fk_veyq_memory_media_bucket_tenant
        foreign key (tenant_id) references veyq.veyq_tenant (id)
            on delete cascade,
    constraint fk_veyq_memory_media_bucket_memory
        foreign key (memory_id) references veyq.veyq_memory (id)
            on delete cascade,
    constraint fk_veyq_memory_media_bucket_parent
        foreign key (parent_bucket_id) references veyq.veyq_memory_media_bucket (id)
            on delete cascade,
    constraint fk_veyq_memory_media_bucket_user
        foreign key (uploader_user_id) references veyq.veyq_user (id)
            on delete cascade,
    constraint fk_veyq_memory_media_bucket_share_identity
        foreign key (share_identity_id) references veyq.veyq_memory_share_identity (id)
            on delete cascade
);

create index idx_veyq_memory_media_bucket_tenant_id
    on veyq.veyq_memory_media_bucket (tenant_id);

create index idx_veyq_memory_media_bucket_memory_id
    on veyq.veyq_memory_media_bucket (memory_id);

create index idx_veyq_memory_media_bucket_parent_id
    on veyq.veyq_memory_media_bucket (parent_bucket_id);

create index idx_veyq_memory_media_bucket_user_id
    on veyq.veyq_memory_media_bucket (uploader_user_id);

create index idx_veyq_memory_media_bucket_share_identity_id
    on veyq.veyq_memory_media_bucket (share_identity_id);

alter table veyq.veyq_media
    add column bucket_id bigint null;

alter table veyq.veyq_media
    add constraint fk_veyq_media_bucket
        foreign key (bucket_id) references veyq.veyq_memory_media_bucket (id)
            on delete set null;

create index idx_veyq_media_bucket_id
    on veyq.veyq_media (bucket_id);
