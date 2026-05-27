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
