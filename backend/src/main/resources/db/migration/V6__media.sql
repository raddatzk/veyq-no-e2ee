-- uc.uploads.add-media — encrypted media attached to a Memory/Chapter node. The media bytes and the
-- light client-side thumbnail derivative are AEAD-sealed under the scope content key; the server stores
-- ciphertext only. It sees just low-sensitivity operational metadata: the node it hangs on, the MIME type,
-- the byte size and takenAt (used to order the gallery — there is no separate manual sort field).
--
-- Dedup (AD-09 convergent/ref-counted), per-uploader buckets and streaming derivatives are later slices.

create table veyq_media (
    id              varchar(64)        primary key,
    scope_id        varchar(64)        not null references veyq_scope (id),
    node_id         varchar(64)        not null references veyq_node (id),
    taken_at        timestamp          not null,  -- server-visible: orders the gallery
    mime_type       varchar(128)       not null,  -- server-visible: image/jpeg, video/mp4, …
    byte_size       bigint             not null,  -- server-visible: plaintext size
    suite_id        varchar(64)        not null,
    sealed_blob     longblob not null, -- nonce || AEAD(content key, media bytes)
    sealed_thumb    longblob,           -- nonce || AEAD(content key, light thumbnail) — optional
    created_at      timestamp          not null
);

create index idx_media_node on veyq_media (node_id);
create index idx_media_scope on veyq_media (scope_id);
