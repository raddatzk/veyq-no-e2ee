create table veyq.veyq_memory_share
(
    id             bigint auto_increment
        primary key,
    public_id      varchar(16)                           not null,
    name           varchar(120)                          null,
    tenant_id      bigint                                not null,
    root_memory_id bigint                                not null,
    role           varchar(40)                           not null,
    password_hash  varchar(255)                          null,
    enabled        bit default 1                         not null,
    created_by     bigint                                not null,
    created_at     timestamp default current_timestamp() not null,
    updated_at     timestamp default current_timestamp() not null,
    constraint uq_veyq_memory_share_public_id
        unique (public_id),
    constraint fk_veyq_memory_share_creator
        foreign key (created_by) references veyq.veyq_user (id)
            on delete cascade,
    constraint fk_veyq_memory_share_memory
        foreign key (root_memory_id) references veyq.veyq_memory (id)
            on delete cascade,
    constraint fk_veyq_memory_share_tenant
        foreign key (tenant_id) references veyq.veyq_tenant (id)
            on delete cascade
);

create index idx_veyq_memory_share_memory_id
    on veyq.veyq_memory_share (root_memory_id);

create index idx_veyq_memory_share_tenant_id
    on veyq.veyq_memory_share (tenant_id);

create table veyq.veyq_memory_share_identity
(
    id           bigint auto_increment
        primary key,
    share_id     bigint                                not null,
    display_name varchar(255)                          not null,
    revoked_at   timestamp                             null,
    created_at   timestamp default current_timestamp() not null,
    updated_at   timestamp default current_timestamp() not null,
    constraint fk_veyq_memory_share_identity_share
        foreign key (share_id) references veyq.veyq_memory_share (id)
            on delete cascade
);

create index idx_veyq_memory_share_identity_share_id
    on veyq.veyq_memory_share_identity (share_id);

create table veyq.veyq_memory_share_refresh_token
(
    id                  bigint auto_increment
        primary key,
    token_hash          varchar(64)                           not null,
    share_identity_id   bigint                                not null,
    expires_at          timestamp                             not null,
    revoked_at          timestamp                             null,
    created_at          timestamp default current_timestamp() not null,
    last_accessed_at    timestamp default current_timestamp() not null,
    rotated_to          bigint                                null,
    constraint uq_veyq_memory_share_refresh_token_hash
        unique (token_hash),
    constraint fk_veyq_memory_share_refresh_identity
        foreign key (share_identity_id) references veyq.veyq_memory_share_identity (id)
            on delete cascade,
    constraint fk_veyq_memory_share_refresh_rotated_to
        foreign key (rotated_to) references veyq.veyq_memory_share_refresh_token (id)
);

create table veyq.veyq_memory_share_access_token
(
    id                  bigint auto_increment
        primary key,
    token_hash          varchar(64)                           not null,
    share_identity_id   bigint                                not null,
    refresh_token_id    bigint                                not null,
    expires_at          timestamp                             not null,
    revoked_at          timestamp                             null,
    created_at          timestamp default current_timestamp() not null,
    last_accessed_at    timestamp default current_timestamp() not null,
    constraint uq_veyq_memory_share_access_token_hash
        unique (token_hash),
    constraint fk_veyq_memory_share_access_identity
        foreign key (share_identity_id) references veyq.veyq_memory_share_identity (id)
            on delete cascade,
    constraint fk_veyq_memory_share_access_refresh
        foreign key (refresh_token_id) references veyq.veyq_memory_share_refresh_token (id)
            on delete cascade
);

alter table veyq.veyq_media
    add column uploader_user_id bigint null;

update veyq.veyq_media
set uploader_user_id = owner_user_id;

alter table veyq.veyq_media
    add column share_identity_id bigint null;

alter table veyq.veyq_media
    add constraint fk_veyq_media_uploader_user
        foreign key (uploader_user_id) references veyq.veyq_user (id)
            on delete set null;

alter table veyq.veyq_media
    add constraint fk_veyq_media_share_identity
        foreign key (share_identity_id) references veyq.veyq_memory_share_identity (id)
            on delete set null;

alter table veyq.veyq_media
    drop foreign key fk_veyq_media_owner;

drop index idx_veyq_media_owner_user_id on veyq.veyq_media;

alter table veyq.veyq_media
    drop column owner_user_id;
