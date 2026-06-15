-- uc.memory.share — a read-only public link to a Memory subtree. Operator-blind: the share stores the
-- scope content key WRAPPED under a per-share key (AEAD); that share key rides the link fragment and never
-- reaches the server, so the server serves ciphertext + the wrapped key but can't read the content. The
-- shareId is a long unguessable token (the secret link). The server gates which ciphertext a guest gets to
-- the shared node's subtree. No FK to veyq_node (handler-validated), like the other content tables.
create table veyq_share (
    id                 varchar(64)        not null primary key,
    scope_id           varchar(64)        not null,
    node_id            varchar(64)        not null,
    suite_id           varchar(32)        not null,
    wrapped_content_key varbinary(4096)   not null,  -- nonce || AEAD(shareKey, scope content key)
    created_by         varchar(64)        not null,
    created_at         timestamp          not null
);

create index veyq_share_node_idx on veyq_share (node_id);
