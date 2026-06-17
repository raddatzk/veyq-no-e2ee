-- uc.memory.track — a Memory/Chapter may have GPX tracks: each a sealed {name, points} route (ciphertext
-- under the scope content key) the server never reads. Many tracks per node; no FK to veyq_node (mirroring
-- the handler-validated references elsewhere) so a node delete just leaves orphan rows the client ignores.
create table veyq_memory_track (
    id             varchar(64)         not null primary key,
    node_id        varchar(64)         not null,
    scope_id       varchar(64)         not null,
    suite_id       varchar(32)         not null,
    sealed_content longblob not null,  -- nonce || AEAD(content key, {name, points})
    created_at     timestamp           not null
);

create index veyq_memory_track_node_idx on veyq_memory_track (node_id);
