-- uc.uploads.resumable-upload — chunked, resumable media upload. Large media are split into independent
-- AEAD frames; the ciphertext frames live in the BlobStore (NOT the DB — the DB stays lean), and these
-- tables hold only the session state + the chunk manifest (index -> object key). The server is blind: it
-- only ever moves opaque frames and tracks which indices have landed (for resume).

create table veyq_upload_session (
    id            varchar(64)  primary key,
    scope_id      varchar(64)  not null references veyq_scope (id),
    node_id       varchar(64)  not null references veyq_node (id),
    total_chunks  int          not null,
    mime_type     varchar(128) not null,
    byte_size     bigint       not null,  -- plaintext size (server-visible)
    taken_at      timestamp    not null,  -- orders the gallery once finalized
    status        varchar(16)  not null,  -- OPEN | COMPLETED
    created_by    varchar(64)  not null,
    created_at    timestamp    not null
);

-- the chunk manifest: one row per received frame, pointing at the BlobStore object (no bytes in the DB).
create table veyq_media_chunk (
    id           varchar(64)  primary key,
    upload_id    varchar(64)  not null references veyq_upload_session (id),
    chunk_index  int          not null,
    object_key   varchar(256) not null,  -- pointer into the BlobStore (the frame's location)
    byte_size    bigint       not null,  -- ciphertext frame size
    created_at   timestamp    not null,
    constraint uq_chunk unique (upload_id, chunk_index)
);

create index idx_chunk_upload on veyq_media_chunk (upload_id);
