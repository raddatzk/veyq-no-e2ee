create table veyq_memory_favorite (
    id          varchar(64) primary key,
    user_id     varchar(64) not null references veyq_user (id),
    scope_id    varchar(64) not null references veyq_scope (id),
    node_id     varchar(64) not null,
    created_at  timestamp   not null,
    constraint uq_memory_favorite_user_node unique (user_id, node_id)
);
create index idx_memory_favorite_user_scope on veyq_memory_favorite (user_id, scope_id);
create index idx_memory_favorite_node on veyq_memory_favorite (node_id);
