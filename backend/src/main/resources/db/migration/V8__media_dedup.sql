-- uc.uploads.add-media (dedup, AD-09) — split the media into a deduplicated CIPHERTEXT blob + a per-Memory
-- ATTACHMENT. Identical content within a scope yields the same client-computed dedup token, so the server
-- stores the sealed bytes once (UNIQUE(scope_id, dedup_token)) and many attachments reference the one blob
-- (reference-counted). The server never sees plaintext and cannot compute the token (the key is client-held),
-- so it deduplicates blindly with no confirmation-of-file attack.

create table veyq_media_blob (
    id           varchar(64)        primary key,
    scope_id     varchar(64)        not null references veyq_scope (id),
    dedup_token  varchar(255)       not null,  -- blinded: HMAC(dedupKey, SHA256(content)), base64url
    suite_id     varchar(64)        not null,
    sealed_blob  varbinary(67108864) not null, -- nonce || AEAD(content key, media bytes)
    byte_size    bigint             not null,
    created_at   timestamp          not null,
    constraint uq_media_blob unique (scope_id, dedup_token)
);

-- the attachment now references the deduplicated blob instead of carrying the bytes inline.
-- NB: this is a greenfield, DESTRUCTIVE change — dropping sealed_blob discards any inline media bytes from
-- V6 and does NOT backfill veyq_media_blob (a real migration would move existing rows into blobs first).
-- Backward compatibility is intentionally not required here.
alter table veyq_media drop column sealed_blob;
alter table veyq_media add column blob_id varchar(64) references veyq_media_blob (id);
