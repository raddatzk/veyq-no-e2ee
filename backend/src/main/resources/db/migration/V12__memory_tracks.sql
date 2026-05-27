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
