-- uc.uploads — UNIFIED media model. One upload/download path for all bytes: a "blob" is a deduplicated
-- content chunk-set (a small file = 1 chunk, a large file = N chunks), stored as AEAD frames in the
-- BlobStore (never the DB). A "media" is an attachment that hangs a blob on a Memory/Chapter, plus typed
-- derivatives (thumbnail, preview — themselves blobs, client-generated since the server is blind). Dedup is
-- per (scope, dedup token): identical content uploads once and is referenced many times.
--
-- Greenfield, DESTRUCTIVE: replaces the split add-media (V6/V8) + resumable (V7) tables. No data migration.

drop table if exists veyq_media_chunk;
drop table if exists veyq_media;
drop table if exists veyq_media_blob;
drop table if exists veyq_upload_session;

-- deduplicated content: a chunk-set of sealed frames in the BlobStore (the DB holds only the manifest).
create table veyq_blob (
    id           varchar(64)  primary key,
    scope_id     varchar(64)  not null references veyq_scope (id),
    dedup_token  varchar(255) not null,  -- HMAC(dedupKey, SHA256(content)), base64url
    total_chunks int          not null,
    byte_size    bigint       not null,  -- plaintext size (server-visible)
    suite_id     varchar(64)  not null,
    created_at   timestamp    not null,
    constraint uq_blob unique (scope_id, dedup_token)
);

-- in-progress upload of a new blob (resumable). On complete its chunks are promoted to a veyq_blob.
create table veyq_blob_upload (
    id           varchar(64)  primary key,
    scope_id     varchar(64)  not null references veyq_scope (id),
    dedup_token  varchar(255) not null,
    total_chunks int          not null,
    byte_size    bigint       not null,
    status       varchar(16)  not null,  -- OPEN | COMPLETED
    blob_id      varchar(64),            -- the resulting content blob, set on completion (idempotent re-complete)
    created_by   varchar(64)  not null,
    created_at   timestamp    not null
);

-- one sealed frame. While uploading it points at its upload; once finalized it points at the blob.
create table veyq_blob_chunk (
    id           varchar(64)  primary key,
    upload_id    varchar(64),            -- set while staging, cleared on finalize
    blob_id      varchar(64),            -- set on finalize (the content it belongs to)
    chunk_index  int          not null,
    object_key   varchar(256) not null,  -- pointer into the BlobStore
    byte_size    bigint       not null,
    created_at   timestamp    not null,
    constraint uq_blob_chunk_upload unique (upload_id, chunk_index)
);
create index idx_blob_chunk_blob on veyq_blob_chunk (blob_id);

-- a media attachment: a Memory/Chapter references a content blob + ordering metadata.
create table veyq_media (
    id               varchar(64)  primary key,
    scope_id         varchar(64)  not null references veyq_scope (id),
    node_id          varchar(64)  not null references veyq_node (id),
    original_blob_id varchar(64)  not null references veyq_blob (id),
    taken_at         timestamp    not null,  -- orders the gallery
    mime_type        varchar(128) not null,
    created_at       timestamp    not null
);
create index idx_media_node on veyq_media (node_id);

-- typed, client-generated derivatives of a media (thumbnail, preview, ... — each is itself a blob).
create table veyq_media_derivative (
    id        varchar(64) primary key,
    media_id  varchar(64) not null references veyq_media (id),
    kind      varchar(32) not null,  -- THUMBNAIL | PREVIEW
    blob_id   varchar(64) not null references veyq_blob (id),
    constraint uq_media_derivative unique (media_id, kind)
);
