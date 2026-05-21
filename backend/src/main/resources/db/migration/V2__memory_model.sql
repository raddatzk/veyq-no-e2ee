create table veyq.veyq_memory
(
    id               bigint auto_increment
        primary key,
    public_id        varchar(16)                           not null,
    tenant_id        bigint                                not null,
    parent_memory_id bigint                                null,
    owner_user_id    bigint                                not null,
    scope_type       varchar(40)                           not null,
    scope_group_id   bigint                                null,
    scope_user_id    bigint                                null,
    name             varchar(120)                          not null,
    displayName      varchar(255)                          not null,
    cover_media_id   bigint                                null,
    created_at       timestamp default current_timestamp() not null,
    updated_at       timestamp default current_timestamp() not null,
    constraint uq_veyq_memory_public_id
        unique (public_id),
    constraint uq_veyq_memory_name
        unique (tenant_id, parent_memory_id, name),
    constraint fk_veyq_memory_cover
        foreign key (cover_media_id) references veyq.veyq_media (id),
    constraint fk_veyq_memory_owner
        foreign key (owner_user_id) references veyq.veyq_user (id)
            on delete cascade,
    constraint fk_veyq_memory_parent
        foreign key (parent_memory_id) references veyq.veyq_memory (id)
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

create index idx_veyq_memory_scope_group_id
    on veyq.veyq_memory (scope_group_id);

create index idx_veyq_memory_scope_user_id
    on veyq.veyq_memory (scope_user_id);

create index idx_veyq_memory_tenant_id
    on veyq.veyq_memory (tenant_id);

insert into veyq.veyq_memory
    (public_id, tenant_id, parent_memory_id, owner_user_id, scope_type, scope_group_id, scope_user_id, name, displayName, cover_media_id, created_at, updated_at)
select
    public_id,
    tenant_id,
    null,
    owner_user_id,
    scope_type,
    scope_group_id,
    scope_user_id,
    name,
    displayName,
    cover_media_id,
    created_at,
    updated_at
from veyq.veyq_tenant_album;

alter table veyq.veyq_media
    add column memory_id bigint null;

update veyq.veyq_media media
set memory_id = (
    select memory.id
    from veyq.veyq_album_media link
        join veyq.veyq_tenant_album album on album.id = link.album_id
        join veyq.veyq_memory memory on memory.public_id = album.public_id
    where link.media_id = media.id
    order by link.hidden, link.created_at, link.album_id
    limit 1
);

alter table veyq.veyq_media
    add constraint fk_veyq_media_memory
        foreign key (memory_id) references veyq.veyq_memory (id)
            on delete cascade;

create index idx_veyq_media_memory_id
    on veyq.veyq_media (memory_id);
