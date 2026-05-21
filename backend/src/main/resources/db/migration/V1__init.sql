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
