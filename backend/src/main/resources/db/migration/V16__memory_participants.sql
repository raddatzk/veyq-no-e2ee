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
