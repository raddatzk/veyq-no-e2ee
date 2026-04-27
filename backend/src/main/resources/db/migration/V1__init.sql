create table lunva_user
(
    id            bigint       not null auto_increment primary key,
    password_hash varchar(255) not null,
    display_name  varchar(255) not null,
    email         varchar(255) not null unique,
    role          varchar(40)  not null
);

create table lunva_refresh_token
(
    id               bigint      not null auto_increment primary key,
    token_hash       varchar(64) not null unique,
    user_id          bigint      not null,
    expires_at       timestamp   not null,
    revoked_at       timestamp null,
    created_at       timestamp   not null default current_timestamp,
    last_accessed_at timestamp   not null default current_timestamp,
    rotated_to       bigint null,
    constraint fk_lunva_refresh_token_user foreign key (user_id) references lunva_user (id) on delete cascade,
    constraint fk_lunva_refresh_token_rotated_to foreign key (rotated_to) references lunva_refresh_token (id)
);

create index idx_lunva_refresh_token_user_id on lunva_refresh_token (user_id);
create index idx_lunva_refresh_token_expires_at on lunva_refresh_token (expires_at);
create index idx_lunva_refresh_token_revoked_at on lunva_refresh_token (revoked_at);

create table lunva_access_token
(
    id               bigint      not null auto_increment primary key,
    token_hash       varchar(64) not null unique,
    user_id          bigint      not null,
    expires_at       timestamp   not null,
    revoked_at       timestamp null,
    created_at       timestamp   not null default current_timestamp,
    last_accessed_at timestamp   not null default current_timestamp,
    refresh_token_id bigint      not null,
    constraint fk_lunva_access_token_user foreign key (user_id) references lunva_user (id) on delete cascade,
    constraint fk_lunva_access_token_refresh_token foreign key (refresh_token_id) references lunva_refresh_token (id)
);

create index idx_lunva_access_token_user_id on lunva_access_token (user_id);
create index idx_lunva_access_token_expires_at on lunva_access_token (expires_at);
create index idx_lunva_access_token_revoked_at on lunva_access_token (revoked_at);

create table lunva_config
(
    id           bigint       not null auto_increment primary key,
    config_key   varchar(120) not null unique,
    config_value varchar(255) not null
);

create table lunva_tenant
(
    id          bigint       not null auto_increment primary key,
    name        varchar(120) not null unique,
    displayName varchar(255) not null
);

create table lunva_tenant_member
(
    tenant_id bigint      not null,
    user_id   bigint      not null,
    role      varchar(40) not null,
    primary key (tenant_id, user_id),
    constraint fk_lunva_tenant_member_tenant foreign key (tenant_id) references lunva_tenant (id) on delete cascade,
    constraint fk_lunva_tenant_member_user foreign key (user_id) references lunva_user (id) on delete cascade
);

create index idx_lunva_tenant_member_user_id on lunva_tenant_member (user_id);

create table lunva_tenant_group
(
    id          bigint       not null auto_increment primary key,
    tenant_id   bigint       not null,
    name        varchar(255) not null,
    displayName varchar(255) not null,
    constraint fk_lunva_tenant_group_tenant foreign key (tenant_id) references lunva_tenant (id) on delete cascade,
    constraint uq_lunva_tenant_group_name unique (tenant_id, name)
);

create index idx_lunva_tenant_group_tenant_id on lunva_tenant_group (tenant_id);

create table lunva_tenant_group_member
(
    group_id bigint      not null,
    user_id  bigint      not null,
    role     varchar(40) not null,
    primary key (group_id, user_id),
    constraint fk_lunva_tenant_group_member_group foreign key (group_id) references lunva_tenant_group (id) on delete cascade,
    constraint fk_lunva_tenant_group_member_user foreign key (user_id) references lunva_user (id) on delete cascade
);

create index idx_lunva_tenant_group_member_user_id on lunva_tenant_group_member (user_id);

create table lunva_media
(
    id            bigint       not null auto_increment primary key,
    public_id varchar(16) not null unique,
    tenant_id     bigint       not null,
    owner_user_id bigint       not null,
    title         varchar(255) not null,
    created_at    timestamp    not null default current_timestamp,
    constraint fk_lunva_media_tenant foreign key (tenant_id) references lunva_tenant (id) on delete cascade,
    constraint fk_lunva_media_owner foreign key (owner_user_id) references lunva_user (id) on delete cascade
);

create index idx_lunva_media_tenant_id on lunva_media (tenant_id);
create index idx_lunva_media_owner_user_id on lunva_media (owner_user_id);

create table lunva_media_user_share
(
    media_id   bigint    not null,
    user_id    bigint    not null,
    created_at timestamp not null default current_timestamp,
    primary key (media_id, user_id),
    constraint fk_lunva_media_user_share_media foreign key (media_id) references lunva_media (id) on delete cascade,
    constraint fk_lunva_media_user_share_user foreign key (user_id) references lunva_user (id) on delete cascade
);

create index idx_lunva_media_user_share_user_id on lunva_media_user_share (user_id);

create table lunva_media_group_share
(
    media_id   bigint    not null,
    group_id   bigint    not null,
    created_at timestamp not null default current_timestamp,
    primary key (media_id, group_id),
    constraint fk_lunva_media_group_share_media foreign key (media_id) references lunva_media (id) on delete cascade,
    constraint fk_lunva_media_group_share_group foreign key (group_id) references lunva_tenant_group (id) on delete cascade
);

create index idx_lunva_media_group_share_group_id on lunva_media_group_share (group_id);
