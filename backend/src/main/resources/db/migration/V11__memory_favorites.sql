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
