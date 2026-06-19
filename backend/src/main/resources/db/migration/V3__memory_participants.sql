create table veyq_memory_participant (
    id              varchar(64)  primary key,
    node_id         varchar(64)  not null,
    scope_id        varchar(64)  not null,
    suite_id        varchar(64)  not null,
    sealed_payload  longblob     not null,
    created_at      timestamp    not null
);
create index idx_memory_participant_node on veyq_memory_participant (node_id);
create index idx_memory_participant_scope on veyq_memory_participant (scope_id);
