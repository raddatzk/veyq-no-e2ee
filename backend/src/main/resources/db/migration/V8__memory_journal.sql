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
