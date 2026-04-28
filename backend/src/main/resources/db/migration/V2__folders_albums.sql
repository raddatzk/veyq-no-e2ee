create table lunva_tenant_folder
(
    id               bigint       not null auto_increment primary key,
    public_id        varchar(16)   not null unique,
    tenant_id        bigint       not null,
    parent_folder_id bigint null,
    owner_user_id    bigint       not null,
    name             varchar(120) not null,
    displayName      varchar(255) not null,
    created_at       timestamp    not null default current_timestamp,
    updated_at       timestamp    not null default current_timestamp,
    constraint fk_lunva_tenant_folder_tenant foreign key (tenant_id) references lunva_tenant (id) on delete cascade,
    constraint fk_lunva_tenant_folder_parent foreign key (parent_folder_id) references lunva_tenant_folder (id) on delete cascade,
    constraint fk_lunva_tenant_folder_owner foreign key (owner_user_id) references lunva_user (id) on delete cascade,
    constraint uq_lunva_tenant_folder_name unique (tenant_id, parent_folder_id, name)
);

create index idx_lunva_tenant_folder_tenant_id on lunva_tenant_folder (tenant_id);
create index idx_lunva_tenant_folder_parent_id on lunva_tenant_folder (parent_folder_id);
create index idx_lunva_tenant_folder_owner_id on lunva_tenant_folder (owner_user_id);

create table lunva_tenant_album
(
    id               bigint       not null auto_increment primary key,
    public_id        varchar(16)   not null unique,
    tenant_id        bigint       not null,
    parent_folder_id bigint null,
    owner_user_id    bigint       not null,
    name             varchar(120) not null,
    displayName      varchar(255) not null,
    cover_media_id   bigint null,
    created_at       timestamp    not null default current_timestamp,
    updated_at       timestamp    not null default current_timestamp,
    constraint fk_lunva_tenant_album_tenant foreign key (tenant_id) references lunva_tenant (id) on delete cascade,
    constraint fk_lunva_tenant_album_parent foreign key (parent_folder_id) references lunva_tenant_folder (id) on delete cascade,
    constraint fk_lunva_tenant_album_owner foreign key (owner_user_id) references lunva_user (id) on delete cascade,
    constraint fk_lunva_tenant_album_cover foreign key (cover_media_id) references lunva_media (id),
    constraint uq_lunva_tenant_album_name unique (tenant_id, parent_folder_id, name)
);

create index idx_lunva_tenant_album_tenant_id on lunva_tenant_album (tenant_id);
create index idx_lunva_tenant_album_parent_id on lunva_tenant_album (parent_folder_id);
create index idx_lunva_tenant_album_owner_id on lunva_tenant_album (owner_user_id);

create table lunva_album_media
(
    album_id   bigint    not null,
    media_id   bigint    not null,
    position   int       not null default 0,
    hidden     boolean   not null default false,
    created_at timestamp not null default current_timestamp,
    primary key (album_id, media_id),
    constraint fk_lunva_album_media_album foreign key (album_id) references lunva_tenant_album (id) on delete cascade,
    constraint fk_lunva_album_media_media foreign key (media_id) references lunva_media (id) on delete cascade
);

create index idx_lunva_album_media_media_id on lunva_album_media (media_id);

create table lunva_tenant_folder_user_share
(
    folder_id bigint not null,
    user_id   bigint not null,
    primary key (folder_id, user_id),
    constraint fk_lunva_folder_user_share_folder foreign key (folder_id) references lunva_tenant_folder (id) on delete cascade,
    constraint fk_lunva_folder_user_share_user foreign key (user_id) references lunva_user (id) on delete cascade
);

create table lunva_tenant_folder_group_share
(
    folder_id bigint not null,
    group_id  bigint not null,
    primary key (folder_id, group_id),
    constraint fk_lunva_folder_group_share_folder foreign key (folder_id) references lunva_tenant_folder (id) on delete cascade,
    constraint fk_lunva_folder_group_share_group foreign key (group_id) references lunva_tenant_group (id) on delete cascade
);

create table lunva_tenant_album_user_share
(
    album_id bigint not null,
    user_id  bigint not null,
    primary key (album_id, user_id),
    constraint fk_lunva_album_user_share_album foreign key (album_id) references lunva_tenant_album (id) on delete cascade,
    constraint fk_lunva_album_user_share_user foreign key (user_id) references lunva_user (id) on delete cascade
);

create table lunva_tenant_album_group_share
(
    album_id bigint not null,
    group_id bigint not null,
    primary key (album_id, group_id),
    constraint fk_lunva_album_group_share_album foreign key (album_id) references lunva_tenant_album (id) on delete cascade,
    constraint fk_lunva_album_group_share_group foreign key (group_id) references lunva_tenant_group (id) on delete cascade
);
