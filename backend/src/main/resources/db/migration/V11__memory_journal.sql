-- uc.memory.journal — a Memory/Chapter has one journal: a sealed Markdown body (ciphertext under the scope
-- content key) the server never reads. One row per node (node_id PK); no FK to veyq_node, mirroring the
-- handler-validated references elsewhere, so a node delete just leaves an orphan row the client ignores.
create table veyq_memory_journal (
    node_id        varchar(64)        not null primary key,
    scope_id       varchar(64)        not null,
    suite_id       varchar(32)        not null,
    sealed_content longblob not null,  -- nonce || AEAD(content key, markdown body)
    updated_at     timestamp          not null
);

create index veyq_memory_journal_scope_idx on veyq_memory_journal (scope_id);
