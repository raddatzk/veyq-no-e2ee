-- Squashed from V1__init.sql

create table veyq.veyq_config
(
    id           bigint auto_increment
        primary key,
    config_key   varchar(120) not null,
    config_value varchar(255) not null,
    constraint config_key
        unique (config_key)
);

create table veyq.veyq_tenant
(
    id          bigint auto_increment
        primary key,
    name        varchar(120) not null,
    displayName varchar(255) not null,
    domain      varchar(255) not null,
    constraint name
        unique (name),
    constraint uq_veyq_tenant_domain
        unique (domain)
);

create table veyq.veyq_tenant_group
(
    id          bigint auto_increment
        primary key,
    tenant_id   bigint       not null,
    name        varchar(255) not null,
    displayName varchar(255) not null,
    constraint uq_veyq_tenant_group_name
        unique (tenant_id, name),
    constraint fk_veyq_tenant_group_tenant
        foreign key (tenant_id) references veyq.veyq_tenant (id)
            on delete cascade
);

create index idx_veyq_tenant_group_tenant_id
    on veyq.veyq_tenant_group (tenant_id);

create table veyq.veyq_user
(
    id              bigint auto_increment
        primary key,
    password_hash   varchar(255) not null,
    display_name    varchar(255) not null,
    email           varchar(255) not null,
    role            varchar(40)  not null,
    password_set_at timestamp    null,
    constraint email
        unique (email)
);

create table veyq.veyq_media
(
    id                   bigint auto_increment
        primary key,
    public_id            varchar(16)                                     not null,
    tenant_id            bigint                                          not null,
    owner_user_id        bigint                                          not null,
    scope_type           varchar(40)                                     not null,
    scope_group_id       bigint                                          null,
    scope_user_id        bigint                                          null,
    title                varchar(255)                                    not null,
    created_at           timestamp    default current_timestamp()        not null,
    mime_type            varchar(255) default 'application/octet-stream' not null,
    size_bytes           bigint       default 0                          not null,
    storage_key          varchar(512) default ''                         not null,
    original_filename    varchar(255)                                    null,
    captured_at          timestamp(6)                                    null,
    captured_at_override tinyint(1)   default 0                          not null,
    camera_make          varchar(255)                                    null,
    camera_model         varchar(255)                                    null,
    lens_model           varchar(255)                                    null,
    image_width          int                                             null,
    image_height         int                                             null,
    gps_latitude         double                                          null,
    gps_longitude        double                                          null,
    constraint uq_veyq_media_public_id
        unique (public_id),
    constraint fk_veyq_media_owner
        foreign key (owner_user_id) references veyq.veyq_user (id)
            on delete cascade,
    constraint fk_veyq_media_scope_group
        foreign key (scope_group_id) references veyq.veyq_tenant_group (id)
            on delete cascade,
    constraint fk_veyq_media_scope_user
        foreign key (scope_user_id) references veyq.veyq_user (id)
            on delete cascade,
    constraint fk_veyq_media_tenant
        foreign key (tenant_id) references veyq.veyq_tenant (id)
            on delete cascade
);

create index idx_veyq_media_owner_user_id
    on veyq.veyq_media (owner_user_id);

create index idx_veyq_media_scope_group_id
    on veyq.veyq_media (scope_group_id);

create index idx_veyq_media_scope_user_id
    on veyq.veyq_media (scope_user_id);

create index idx_veyq_media_tenant_id
    on veyq.veyq_media (tenant_id);

create table veyq.veyq_refresh_token
(
    id               bigint auto_increment
        primary key,
    token_hash       varchar(64)                           not null,
    user_id          bigint                                not null,
    expires_at       timestamp                             not null,
    revoked_at       timestamp                             null,
    created_at       timestamp default current_timestamp() not null,
    last_accessed_at timestamp default current_timestamp() not null,
    rotated_to       bigint                                null,
    constraint uq_veyq_refresh_token_hash
        unique (token_hash),
    constraint fk_veyq_refresh_token_rotated_to
        foreign key (rotated_to) references veyq.veyq_refresh_token (id),
    constraint fk_veyq_refresh_token_user
        foreign key (user_id) references veyq.veyq_user (id)
            on delete cascade
);

create table veyq.veyq_access_token
(
    id               bigint auto_increment
        primary key,
    token_hash       varchar(64)                           not null,
    user_id          bigint                                not null,
    expires_at       timestamp                             not null,
    revoked_at       timestamp                             null,
    created_at       timestamp default current_timestamp() not null,
    last_accessed_at timestamp default current_timestamp() not null,
    refresh_token_id bigint                                not null,
    constraint uq_veyq_access_token_hash
        unique (token_hash),
    constraint fk_veyq_access_token_refresh_token
        foreign key (refresh_token_id) references veyq.veyq_refresh_token (id),
    constraint fk_veyq_access_token_user
        foreign key (user_id) references veyq.veyq_user (id)
            on delete cascade
);

create index idx_veyq_access_token_expires_at
    on veyq.veyq_access_token (expires_at);

create index idx_veyq_access_token_revoked_at
    on veyq.veyq_access_token (revoked_at);

create index idx_veyq_access_token_user_id
    on veyq.veyq_access_token (user_id);

create index idx_veyq_refresh_token_expires_at
    on veyq.veyq_refresh_token (expires_at);

create index idx_veyq_refresh_token_revoked_at
    on veyq.veyq_refresh_token (revoked_at);

create index idx_veyq_refresh_token_user_id
    on veyq.veyq_refresh_token (user_id);

create table veyq.veyq_reset_password_token
(
    id          bigint auto_increment
        primary key,
    user_id     bigint                                not null,
    token_hash  varchar(64)                           not null,
    expires_at  timestamp                             not null,
    accepted_at timestamp                             null,
    revoked_at  timestamp                             null,
    created_at  timestamp default current_timestamp() not null,
    constraint uq_veyq_reset_password_token_hash
        unique (token_hash),
    constraint fk_veyq_reset_password_token_user
        foreign key (user_id) references veyq.veyq_user (id)
            on delete cascade
);

create index idx_veyq_reset_password_token_expires_at
    on veyq.veyq_reset_password_token (expires_at);

create index idx_veyq_reset_password_token_user_id
    on veyq.veyq_reset_password_token (user_id);

create table veyq.veyq_tenant_folder
(
    id               bigint auto_increment
        primary key,
    public_id        varchar(16)                           not null,
    tenant_id        bigint                                not null,
    parent_folder_id bigint                                null,
    owner_user_id    bigint                                not null,
    scope_type       varchar(40)                           not null,
    scope_group_id   bigint                                null,
    scope_user_id    bigint                                null,
    name             varchar(120)                          not null,
    displayName      varchar(255)                          not null,
    created_at       timestamp default current_timestamp() not null,
    updated_at       timestamp default current_timestamp() not null,
    constraint uq_veyq_tenant_folder_public_id
        unique (public_id),
    constraint uq_veyq_tenant_folder_name
        unique (tenant_id, parent_folder_id, name),
    constraint fk_veyq_tenant_folder_owner
        foreign key (owner_user_id) references veyq.veyq_user (id)
            on delete cascade,
    constraint fk_veyq_tenant_folder_parent
        foreign key (parent_folder_id) references veyq.veyq_tenant_folder (id)
            on delete cascade,
    constraint fk_veyq_tenant_folder_scope_group
        foreign key (scope_group_id) references veyq.veyq_tenant_group (id)
            on delete cascade,
    constraint fk_veyq_tenant_folder_scope_user
        foreign key (scope_user_id) references veyq.veyq_user (id)
            on delete cascade,
    constraint fk_veyq_tenant_folder_tenant
        foreign key (tenant_id) references veyq.veyq_tenant (id)
            on delete cascade
);

create index idx_veyq_tenant_folder_owner_id
    on veyq.veyq_tenant_folder (owner_user_id);

create index idx_veyq_tenant_folder_parent_id
    on veyq.veyq_tenant_folder (parent_folder_id);

create index idx_veyq_tenant_folder_scope_group_id
    on veyq.veyq_tenant_folder (scope_group_id);

create index idx_veyq_tenant_folder_scope_user_id
    on veyq.veyq_tenant_folder (scope_user_id);

create index idx_veyq_tenant_folder_tenant_id
    on veyq.veyq_tenant_folder (tenant_id);

create table veyq.veyq_tenant_group_member
(
    group_id bigint      not null,
    user_id  bigint      not null,
    role     varchar(40) not null,
    primary key (group_id, user_id),
    constraint fk_veyq_tenant_group_member_group
        foreign key (group_id) references veyq.veyq_tenant_group (id)
            on delete cascade,
    constraint fk_veyq_tenant_group_member_user
        foreign key (user_id) references veyq.veyq_user (id)
            on delete cascade
);

create index idx_veyq_tenant_group_member_user_id
    on veyq.veyq_tenant_group_member (user_id);

create table veyq.veyq_tenant_member
(
    tenant_id   bigint      not null,
    user_id     bigint      not null,
    role        varchar(40) not null,
    accepted_at timestamp   null,
    primary key (tenant_id, user_id),
    constraint fk_veyq_tenant_member_tenant
        foreign key (tenant_id) references veyq.veyq_tenant (id)
            on delete cascade,
    constraint fk_veyq_tenant_member_user
        foreign key (user_id) references veyq.veyq_user (id)
            on delete cascade
);

create index idx_veyq_tenant_member_user_id
    on veyq.veyq_tenant_member (user_id);

create table veyq.veyq_user_email_change
(
    id            bigint auto_increment
        primary key,
    user_id       bigint                                not null,
    pending_email varchar(255)                          not null,
    token_hash    varchar(64)                           not null,
    expires_at    timestamp                             not null,
    accepted_at   timestamp                             null,
    revoked_at    timestamp                             null,
    created_at    timestamp default current_timestamp() not null,
    constraint uq_veyq_user_email_change_token_hash
        unique (token_hash),
    constraint fk_veyq_user_email_change_user
        foreign key (user_id) references veyq.veyq_user (id)
            on delete cascade
);

create index idx_veyq_user_email_change_expires_at
    on veyq.veyq_user_email_change (expires_at);

create index idx_veyq_user_email_change_pending_email
    on veyq.veyq_user_email_change (pending_email);

create index idx_veyq_user_email_change_user_id
    on veyq.veyq_user_email_change (user_id);

create table veyq.veyq_user_invitation
(
    id          bigint auto_increment
        primary key,
    user_id     bigint                                not null,
    token_hash  varchar(64)                           not null,
    expires_at  timestamp                             not null,
    accepted_at timestamp                             null,
    revoked_at  timestamp                             null,
    created_at  timestamp default current_timestamp() not null,
    tenant_id   bigint                                not null,
    constraint uq_veyq_user_invitation_token_hash
        unique (token_hash),
    constraint fk_veyq_user_invitation_tenant
        foreign key (tenant_id) references veyq.veyq_tenant (id)
            on delete cascade,
    constraint fk_veyq_user_invitation_user
        foreign key (user_id) references veyq.veyq_user (id)
            on delete cascade
);

create index idx_veyq_user_invitation_expires_at
    on veyq.veyq_user_invitation (expires_at);

create index idx_veyq_user_invitation_tenant_id
    on veyq.veyq_user_invitation (tenant_id);

create index idx_veyq_user_invitation_user_id
    on veyq.veyq_user_invitation (user_id);


-- Squashed from V2__memory_model.sql

create table veyq.veyq_memory
(
    id               bigint auto_increment
        primary key,
    public_id        varchar(16)                           not null,
    tenant_id        bigint                                not null,
    parent_memory_id bigint                                null,
    parent_folder_id bigint                                null,
    owner_user_id    bigint                                not null,
    scope_type       varchar(40)                           not null,
    scope_group_id   bigint                                null,
    scope_user_id    bigint                                null,
    name             varchar(120)                          not null,
    displayName      varchar(255)                          not null,
    cover_media_id   bigint                                null,
    cover_storage_key varchar(500)                         null,
    cover_mime_type  varchar(120)                          null,
    cover_original_filename varchar(255)                   null,
    created_at       timestamp default current_timestamp() not null,
    updated_at       timestamp default current_timestamp() not null,
    constraint uq_veyq_memory_public_id
        unique (public_id),
    constraint uq_veyq_memory_name
        unique (tenant_id, parent_memory_id, parent_folder_id, name),
    constraint fk_veyq_memory_cover
        foreign key (cover_media_id) references veyq.veyq_media (id),
    constraint fk_veyq_memory_owner
        foreign key (owner_user_id) references veyq.veyq_user (id)
            on delete cascade,
    constraint fk_veyq_memory_parent
        foreign key (parent_memory_id) references veyq.veyq_memory (id)
            on delete cascade,
    constraint fk_veyq_memory_parent_folder
        foreign key (parent_folder_id) references veyq.veyq_tenant_folder (id)
            on delete cascade,
    constraint fk_veyq_memory_scope_group
        foreign key (scope_group_id) references veyq.veyq_tenant_group (id)
            on delete cascade,
    constraint fk_veyq_memory_scope_user
        foreign key (scope_user_id) references veyq.veyq_user (id)
            on delete cascade,
    constraint fk_veyq_memory_tenant
        foreign key (tenant_id) references veyq.veyq_tenant (id)
            on delete cascade
);

create index idx_veyq_memory_owner_id
    on veyq.veyq_memory (owner_user_id);

create index idx_veyq_memory_parent_id
    on veyq.veyq_memory (parent_memory_id);

create index idx_veyq_memory_parent_folder_id
    on veyq.veyq_memory (parent_folder_id);

create index idx_veyq_memory_scope_group_id
    on veyq.veyq_memory (scope_group_id);

create index idx_veyq_memory_scope_user_id
    on veyq.veyq_memory (scope_user_id);

create index idx_veyq_memory_tenant_id
    on veyq.veyq_memory (tenant_id);

alter table veyq.veyq_media
    add column memory_id bigint null;

alter table veyq.veyq_media
    add constraint fk_veyq_media_memory
        foreign key (memory_id) references veyq.veyq_memory (id)
            on delete cascade;

create index idx_veyq_media_memory_id
    on veyq.veyq_media (memory_id);


-- Squashed from V3__passkeys.sql

create table veyq.veyq_passkey_credential
(
    id                   bigint auto_increment
        primary key,
    user_id              bigint                                not null,
    credential_id        varchar(512)                          not null,
    public_key           tinyblob                                  not null,
    public_key_algorithm bigint                                not null,
    counter              bigint                                not null,
    aaguid               varchar(36)                           not null,
    transports           varchar(255)                          null,
    display_name         varchar(255)                          not null,
    created_at           timestamp default current_timestamp() not null,
    last_used_at         timestamp                             null,
    constraint uq_veyq_passkey_credential_id
        unique (credential_id),
    constraint fk_veyq_passkey_credential_user
        foreign key (user_id) references veyq.veyq_user (id)
            on delete cascade
);

create index idx_veyq_passkey_credential_user_id
    on veyq.veyq_passkey_credential (user_id);


-- Squashed from V4__memory_date_range.sql

alter table veyq.veyq_memory
    add column start_date date null;

alter table veyq.veyq_memory
    add column end_date date null;

update veyq.veyq_memory memory
set start_date = coalesce(
        (
            select cast(min(coalesce(media.captured_at, media.created_at)) as date)
            from veyq.veyq_media media
            where media.memory_id = memory.id
        ),
        cast(memory.created_at as date)
    ),
    end_date = coalesce(
        (
            select cast(max(coalesce(media.captured_at, media.created_at)) as date)
            from veyq.veyq_media media
            where media.memory_id = memory.id
        ),
        cast(memory.created_at as date)
    );

alter table veyq.veyq_memory
    modify column start_date date not null;

alter table veyq.veyq_memory
    modify column end_date date not null;


-- Squashed from V5__memory_cover_crop.sql

alter table veyq.veyq_memory
    add column cover_crop_x double precision null;

alter table veyq.veyq_memory
    add column cover_crop_y double precision null;

alter table veyq.veyq_memory
    add column cover_crop_width double precision null;

alter table veyq.veyq_memory
    add column cover_crop_height double precision null;


-- Squashed from V6__memory_public_shares.sql

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


-- Squashed from V7__user_profile_permalinks.sql

alter table veyq.veyq_user
    add column permalink varchar(120) null;

update veyq.veyq_user
set permalink = concat('user-', substring(replace(uuid(), '-', ''), 1, 8));

alter table veyq.veyq_user
    modify column permalink varchar(120) not null;

create unique index uq_veyq_user_permalink
    on veyq.veyq_user (permalink);


-- Squashed from V8__memory_journal.sql

create table veyq.veyq_memory_journal
(
    id         bigint auto_increment
        primary key,
    memory_id  bigint                                not null,
    markdown   longtext                              not null,
    created_at timestamp default current_timestamp() not null,
    updated_at timestamp default current_timestamp() not null,
    constraint uq_veyq_memory_journal_memory_id
        unique (memory_id),
    constraint fk_veyq_memory_journal_memory
        foreign key (memory_id) references veyq.veyq_memory (id)
            on delete cascade
);


-- Squashed from V9__media_archive_download_index.sql

create index idx_veyq_media_archive_memory_sort
    on veyq.veyq_media (memory_id, captured_at, created_at, id);


-- Squashed from V10__media_blobs.sql

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


-- Squashed from V11__memory_favorites.sql

create table veyq.veyq_memory_favorite
(
    id         bigint auto_increment
        primary key,
    tenant_id  bigint                                not null,
    user_id    bigint                                not null,
    memory_id  bigint                                not null,
    created_at timestamp default current_timestamp() not null,
    constraint uq_veyq_memory_favorite_user_memory
        unique (tenant_id, user_id, memory_id),
    constraint fk_veyq_memory_favorite_tenant
        foreign key (tenant_id) references veyq.veyq_tenant (id)
            on delete cascade,
    constraint fk_veyq_memory_favorite_user
        foreign key (user_id) references veyq.veyq_user (id)
            on delete cascade,
    constraint fk_veyq_memory_favorite_memory
        foreign key (memory_id) references veyq.veyq_memory (id)
            on delete cascade
);

create index idx_veyq_memory_favorite_tenant_user_created
    on veyq.veyq_memory_favorite (tenant_id, user_id, created_at);

create index idx_veyq_memory_favorite_memory_id
    on veyq.veyq_memory_favorite (memory_id);


-- Squashed from V12__memory_tracks.sql

create table veyq.veyq_memory_track
(
    id                  bigint auto_increment
        primary key,
    public_id           varchar(16)                            not null,
    tenant_id           bigint                                 not null,
    memory_id           bigint                                 not null,
    scope_type          varchar(40)                            not null,
    scope_group_id      bigint                                 null,
    scope_user_id       bigint                                 null,
    title               varchar(160)                           not null,
    activity_type       varchar(64)                            not null,
    original_filename   varchar(255)                           not null,
    storage_key         varchar(512)                           not null,
    size_bytes          bigint                                 not null,
    distance_meters     bigint                                 not null,
    point_count         int                                    not null,
    started_at          timestamp(6)                           null,
    ended_at            timestamp(6)                           null,
    min_latitude        double                                 not null,
    min_longitude       double                                 not null,
    max_latitude        double                                 not null,
    max_longitude       double                                 not null,
    geometry_json       mediumtext                             not null,
    created_at          timestamp default current_timestamp()  not null,
    updated_at          timestamp default current_timestamp()  not null,
    constraint uq_veyq_memory_track_public_id
        unique (public_id),
    constraint fk_veyq_memory_track_memory
        foreign key (memory_id) references veyq.veyq_memory (id)
            on delete cascade,
    constraint fk_veyq_memory_track_scope_group
        foreign key (scope_group_id) references veyq.veyq_tenant_group (id)
            on delete cascade,
    constraint fk_veyq_memory_track_scope_user
        foreign key (scope_user_id) references veyq.veyq_user (id)
            on delete cascade,
    constraint fk_veyq_memory_track_tenant
        foreign key (tenant_id) references veyq.veyq_tenant (id)
            on delete cascade
);

create index idx_veyq_memory_track_memory_id
    on veyq.veyq_memory_track (memory_id);

create index idx_veyq_memory_track_scope_group_id
    on veyq.veyq_memory_track (scope_group_id);

create index idx_veyq_memory_track_scope_user_id
    on veyq.veyq_memory_track (scope_user_id);

create index idx_veyq_memory_track_tenant_memory
    on veyq.veyq_memory_track (tenant_id, memory_id, created_at, id);


-- Squashed from V13__memory_playlists.sql

create table veyq.veyq_memory_playlist
(
    id                           bigint auto_increment
        primary key,
    public_id                    varchar(16)                           not null,
    tenant_id                    bigint                                not null,
    memory_id                    bigint                                not null,
    scope_type                   varchar(40)                           not null,
    scope_group_id               bigint                                null,
    scope_user_id                bigint                                null,
    title                        varchar(160)                          not null,
    note                         varchar(1000)                         null,
    provider                     varchar(40)                           not null,
    url                          varchar(1000)                         not null,
    external_id                  varchar(255)                          not null,
    created_by_user_id           bigint                                null,
    created_by_share_identity_id bigint                                null,
    created_at                   timestamp default current_timestamp() not null,
    updated_at                   timestamp default current_timestamp() not null,
    constraint uq_veyq_memory_playlist_public_id
        unique (public_id),
    constraint fk_veyq_memory_playlist_created_by_share_identity
        foreign key (created_by_share_identity_id) references veyq.veyq_memory_share_identity (id)
            on delete set null,
    constraint fk_veyq_memory_playlist_created_by_user
        foreign key (created_by_user_id) references veyq.veyq_user (id)
            on delete set null,
    constraint fk_veyq_memory_playlist_memory
        foreign key (memory_id) references veyq.veyq_memory (id)
            on delete cascade,
    constraint fk_veyq_memory_playlist_scope_group
        foreign key (scope_group_id) references veyq.veyq_tenant_group (id)
            on delete cascade,
    constraint fk_veyq_memory_playlist_scope_user
        foreign key (scope_user_id) references veyq.veyq_user (id)
            on delete cascade,
    constraint fk_veyq_memory_playlist_tenant
        foreign key (tenant_id) references veyq.veyq_tenant (id)
            on delete cascade
);

create index idx_veyq_memory_playlist_memory_id
    on veyq.veyq_memory_playlist (memory_id);

create index idx_veyq_memory_playlist_scope_group_id
    on veyq.veyq_memory_playlist (scope_group_id);

create index idx_veyq_memory_playlist_scope_user_id
    on veyq.veyq_memory_playlist (scope_user_id);

create index idx_veyq_memory_playlist_tenant_memory
    on veyq.veyq_memory_playlist (tenant_id, memory_id, created_at, id);


-- Squashed from V14__memory_track_elevation.sql

alter table veyq.veyq_memory_track
    add column ascent_meters bigint null;

alter table veyq.veyq_memory_track
    add column descent_meters bigint null;


-- Squashed from V15__memory_playlist_featured.sql

alter table veyq.veyq_memory_playlist
    add column featured boolean default false not null;

create index idx_veyq_memory_playlist_featured
    on veyq.veyq_memory_playlist (memory_id, featured, created_at, id);


-- Squashed from V16__memory_participants.sql

create table veyq.veyq_memory_participant
(
    id               bigint auto_increment
        primary key,
    tenant_id        bigint                                not null,
    memory_id        bigint                                not null,
    user_id          bigint                                not null,
    added_by_user_id bigint                                not null,
    created_at       timestamp default current_timestamp() not null,
    constraint uq_veyq_memory_participant_memory_user
        unique (memory_id, user_id),
    constraint fk_veyq_memory_participant_tenant
        foreign key (tenant_id) references veyq.veyq_tenant (id)
            on delete cascade,
    constraint fk_veyq_memory_participant_memory
        foreign key (memory_id) references veyq.veyq_memory (id)
            on delete cascade,
    constraint fk_veyq_memory_participant_user
        foreign key (user_id) references veyq.veyq_user (id)
            on delete cascade,
    constraint fk_veyq_memory_participant_added_by
        foreign key (added_by_user_id) references veyq.veyq_user (id)
            on delete cascade
);

create index idx_veyq_memory_participant_tenant_user
    on veyq.veyq_memory_participant (tenant_id, user_id);

create index idx_veyq_memory_participant_memory_id
    on veyq.veyq_memory_participant (memory_id);


-- Squashed from V17__user_profile_avatars.sql

alter table veyq.veyq_user
    add column avatar_storage_key varchar(512);

alter table veyq.veyq_user
    add column avatar_content_type varchar(64);

alter table veyq.veyq_user
    add column avatar_updated_at timestamp;


-- Squashed from V18__share_notices.sql

alter table veyq.veyq_tenant
    add column terms_markdown_by_locale text null;

alter table veyq.veyq_tenant
    add column terms_version int default 0 not null;

alter table veyq.veyq_memory_share
    add column notice_markdown_by_locale text null;

alter table veyq.veyq_memory_share
    add column notice_version int default 0 not null;


-- Squashed from V19__tenant_logos.sql

alter table veyq.veyq_tenant
    add column logo_storage_key varchar(512);

alter table veyq.veyq_tenant
    add column logo_content_type varchar(64);

alter table veyq.veyq_tenant
    add column logo_updated_at timestamp;


-- Squashed from V20__media_thumbnails.sql

alter table veyq.veyq_media_blob
    add column thumbnail_storage_key varchar(512) null;


-- Squashed from V21__memory_media_buckets.sql

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


-- Squashed from V22__video_posters.sql

ALTER TABLE veyq_media_blob ADD COLUMN poster_storage_key varchar(512) NULL;


-- Squashed from V23__server_update_notifications.sql

create table veyq.veyq_server_update_event
(
    id           bigint auto_increment
        primary key,
    status       varchar(40)                           not null,
    from_version varchar(80)                           null,
    to_version   varchar(80)                           null,
    image        varchar(255)                          not null,
    digest       varchar(255)                          null,
    stage        varchar(40)                           null,
    occurred_at  timestamp(6)                          not null,
    created_at   timestamp default current_timestamp() not null
);

create index idx_veyq_server_update_event_occurred_at
    on veyq.veyq_server_update_event (occurred_at);

create table veyq.veyq_server_notification
(
    id              bigint auto_increment
        primary key,
    notification_type varchar(80)                          not null,
    update_event_id bigint                                null,
    created_at      timestamp default current_timestamp() not null,
    constraint fk_veyq_server_notification_update_event
        foreign key (update_event_id) references veyq.veyq_server_update_event (id)
            on delete cascade
);

create index idx_veyq_server_notification_type
    on veyq.veyq_server_notification (notification_type);

create table veyq.veyq_user_capability_ack
(
    id            bigint auto_increment
        primary key,
    user_id       bigint                                not null,
    capability_id varchar(120)                          not null,
    dismissed_at  timestamp default current_timestamp() not null,
    constraint uq_veyq_user_capability_ack
        unique (user_id, capability_id),
    constraint fk_veyq_user_capability_ack_user
        foreign key (user_id) references veyq.veyq_user (id)
            on delete cascade
);

create index idx_veyq_user_capability_ack_user_id
    on veyq.veyq_user_capability_ack (user_id);


-- Squashed from V24__passkey_domain_linking.sql

alter table veyq.veyq_passkey_credential
    add column relying_party_id varchar(255) null after display_name;

create index idx_veyq_passkey_credential_rp_id
    on veyq.veyq_passkey_credential (relying_party_id);

create table veyq.veyq_passkey_link_token
(
    id               bigint auto_increment
        primary key,
    code_hash        varchar(64)                           not null,
    grant_hash       varchar(64)                           null,
    user_id          bigint                                not null,
    auth_domain      varchar(255)                          not null,
    target_domain    varchar(255)                          not null,
    redirect_path    varchar(1024)                         not null,
    expires_at       timestamp                             not null,
    exchanged_at     timestamp                             null,
    consumed_at      timestamp                             null,
    created_at       timestamp default current_timestamp() not null,
    constraint uq_veyq_passkey_link_code_hash
        unique (code_hash),
    constraint uq_veyq_passkey_link_grant_hash
        unique (grant_hash),
    constraint fk_veyq_passkey_link_token_user
        foreign key (user_id) references veyq.veyq_user (id)
            on delete cascade
);

create index idx_veyq_passkey_link_token_user_id
    on veyq.veyq_passkey_link_token (user_id);

create index idx_veyq_passkey_link_token_target_domain
    on veyq.veyq_passkey_link_token (target_domain);


-- Squashed from V25__reset_token_domain.sql

alter table veyq.veyq_reset_password_token
    add column reset_domain varchar(255) null after user_id;

create index idx_veyq_reset_password_token_reset_domain
    on veyq.veyq_reset_password_token (reset_domain);


-- Squashed from V26__mobile_sessions.sql

create table veyq_mobile_session_code (
    id bigint not null auto_increment,
    code_hash varchar(64) not null,
    user_id bigint not null,
    redirect_uri varchar(1024) not null,
    state varchar(512),
    expires_at timestamp(6) not null,
    consumed_at timestamp(6),
    created_at timestamp(6) not null,
    primary key (id),
    constraint uk_mobile_session_code_hash unique (code_hash),
    constraint fk_mobile_session_code_user foreign key (user_id) references veyq_user(id)
);


-- Squashed from V27__tenant_activation.sql

alter table veyq.veyq_tenant
    add column activated_at timestamp null;

update veyq.veyq_tenant
set activated_at = current_timestamp()
where activated_at is null;


-- Squashed from V28__user_lifecycle.sql

alter table veyq.veyq_user
    add column disabled_at timestamp null;

alter table veyq.veyq_user
    add column disabled_by_user_id bigint null;

alter table veyq.veyq_user
    add column deleted_at timestamp null;

alter table veyq.veyq_user
    add column deleted_replacement_user_id bigint null;

alter table veyq.veyq_user
    add column system_account tinyint(1) default 0 not null;

alter table veyq.veyq_user
    add constraint fk_veyq_user_disabled_by
        foreign key (disabled_by_user_id) references veyq.veyq_user (id)
            on delete set null;

alter table veyq.veyq_user
    add constraint fk_veyq_user_deleted_replacement
        foreign key (deleted_replacement_user_id) references veyq.veyq_user (id)
            on delete set null;

create index idx_veyq_user_disabled_at
    on veyq.veyq_user (disabled_at);

create index idx_veyq_user_deleted_at
    on veyq.veyq_user (deleted_at);

create index idx_veyq_user_system_user
    on veyq.veyq_user (system_account);

insert into veyq.veyq_user (
    password_hash,
    display_name,
    permalink,
    email,
    role,
    password_set_at,
    system_account
)
select
    'system-user-no-login',
    'Deleted User',
    'deleted-user',
    'deleted-user@system.veyq.local',
    'ServerMember',
    null,
    1
where not exists (
    select 1
    from veyq.veyq_user
    where system_account = 1 and permalink = 'deleted-user'
);

alter table veyq.veyq_tenant_member
    add column status varchar(40) null;

alter table veyq.veyq_tenant_member
    add column removed_at timestamp null;

alter table veyq.veyq_tenant_member
    add column removed_by_user_id bigint null;

update veyq.veyq_tenant_member
set status = case
    when accepted_at is null then 'Pending'
    else 'Active'
end
where status is null;

alter table veyq.veyq_tenant_member
    modify column status varchar(40) not null;

alter table veyq.veyq_tenant_member
    add constraint fk_veyq_tenant_member_removed_by
        foreign key (removed_by_user_id) references veyq.veyq_user (id)
            on delete set null;

create index idx_veyq_tenant_member_status
    on veyq.veyq_tenant_member (status);


-- Squashed from V29__memory_media_live_updates_index.sql

create index idx_veyq_media_memory_created_public
    on veyq.veyq_media (memory_id, created_at, public_id);


-- Squashed from V30__totp_password_login.sql

create table veyq.veyq_totp_credential
(
    id           bigint auto_increment
        primary key,
    user_id      bigint                                not null,
    secret         varchar(64)                           not null,
    created_at     timestamp default current_timestamp() not null,
    enabled_at     timestamp                             not null,
    last_used_at   timestamp                             null,
    last_used_step bigint                                null,
    constraint uq_veyq_totp_credential_user
        unique (user_id),
    constraint fk_veyq_totp_credential_user
        foreign key (user_id) references veyq.veyq_user (id)
            on delete cascade
);

create index idx_veyq_totp_credential_user_id
    on veyq.veyq_totp_credential (user_id);


