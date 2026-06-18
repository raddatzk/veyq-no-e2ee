-- Consolidated greenfield baseline. This single script replaces the former incremental chain (the old
-- V1..V30) with the final end-state schema. We always start from a fresh database, so no step-by-step
-- migration history is needed — every table is created directly in its final shape. Only portable CREATE
-- syntax is used, so this applies identically on H2 (dev/test) and MariaDB (DB_KIND=mariadb). The earlier
-- ALTER/DROP steps that diverged between the two engines (drop-index, alter-column-set-null) are gone:
-- a fresh CREATE declares the final columns/nullability/indexes outright.
--
-- Blind store: every key/blob/sealed_* column holds opaque ciphertext + a suite id; the server never
-- interprets the bytes (see docs/wiki/crosscutting/crypto-spec.md). Several content tables intentionally
-- carry NO foreign key on their node/scope reference (e.g. node.parent_id, *.node_id on journal/track/
-- share/playlist/bucket, media.bucket_id, node.cover_media_id) — those edges are validated in the handler,
-- so a delete just leaves an orphan row the client ignores.

-- ── Identity & key store ─────────────────────────────────────────────────────────────────────────────
create table veyq_user (
    id              varchar(64)  primary key,
    email           varchar(320) not null unique,
    display_name    varchar(255) not null,
    server_role     varchar(32)  not null,
    system_account  boolean      not null default false,
    created_at      timestamp    not null
);

create table veyq_identity_key (
    id                        varchar(64)     primary key,
    user_id                   varchar(64)     not null unique references veyq_user (id),
    suite_id                  varchar(64)     not null,
    kem_public_key            varbinary(8192) not null,
    sign_public_key           varbinary(1024) not null,
    wrapped_private_key       varbinary(8192) not null,
    wrapped_private_suite_id  varchar(64)     not null,
    created_at                timestamp       not null
);

create table veyq_key_blob (
    id                 varchar(64)     primary key,
    user_id            varchar(64)     not null references veyq_user (id),
    unlock_method      varchar(32)     not null,
    suite_id           varchar(64)     not null,
    wrapped_key        varbinary(8192) not null,
    kdf_params         varbinary(2048),
    credential_ref     varchar(255),
    created_at         timestamp       not null,
    recovery_auth_tag  varbinary(64)                        -- SHA-256("veyq/v1/recovery-auth" || masterKey)
);
create index idx_key_blob_user on veyq_key_blob (user_id);
create index idx_key_blob_user_method on veyq_key_blob (user_id, unlock_method);

create table veyq_auth_verifier (
    id          varchar(64)     primary key,
    user_id     varchar(64)     not null references veyq_user (id),
    kind        varchar(32)     not null,
    suite_id    varchar(64)     not null,
    verifier    varbinary(8192) not null,
    created_at  timestamp       not null
);

-- Opaque server sessions (auth != unlock). Only the SHA-256 of the bearer token is stored, never the raw token.
create table veyq_session (
    id          varchar(64)  primary key,
    token_hash  varchar(128) not null unique,
    user_id     varchar(64)  not null references veyq_user (id),
    created_at  timestamp    not null,
    expires_at  timestamp    not null
);
create index idx_session_user on veyq_session (user_id);

-- ── Tenancy & scopes ─────────────────────────────────────────────────────────────────────────────────
create table veyq_tenant (
    id                     varchar(64)  primary key,
    name                   varchar(255) not null unique,
    display_name           varchar(255) not null,
    domain                 varchar(255) not null,            -- domain-scoped: resolves prefix-less member URLs
    status                 varchar(32)  not null,            -- PENDING, ACTIVE
    created_by             varchar(64)  not null references veyq_user (id),
    created_at             timestamp    not null,
    terms_markdown         text,                             -- tenant-level guest ToS (plaintext policy)
    terms_version          integer      not null default 0,
    scheduled_deletion_at  timestamp,                        -- null = not scheduled for grace-period deletion
    constraint uq_tenant_domain unique (domain)
);

create table veyq_tenant_invite (
    id                              varchar(64)    primary key,
    tenant_id                       varchar(64)    not null references veyq_tenant (id),
    role                            varchar(32)    not null,
    secret_hash                     varbinary(64)  not null,  -- SHA-256 of the one-time bootstrap secret
    status                          varchar(32)    not null,  -- PENDING, CONSUMED, REVOKED
    kind                            varchar(16)    not null default 'OWNER',  -- OWNER (mint scope) | MEMBER (temp-wrap)
    scope_id                        varchar(64),              -- MEMBER: granted scope (no FK — created later at runtime)
    temp_public_key                 varbinary(2048),
    temp_wrapped_scope_private_key  varbinary(8192),
    temp_wrap_suite_id              varchar(64),
    consumed_by                     varchar(64)    references veyq_user (id),
    created_by                      varchar(64)    not null references veyq_user (id),
    created_at                      timestamp      not null,
    expires_at                      timestamp      not null
);

-- A tenant has one TENANT_ROOT plus many GROUP_ROOT scopes; personal scopes have a null tenant_id. The
-- "one TENANT_ROOT per tenant" rule is enforced in application code (tenant_id is only non-unique-indexed
-- here, which also backs the FK on MariaDB).
create table veyq_scope (
    id              varchar(64)     primary key,
    tenant_id       varchar(64)     references veyq_tenant (id),
    owner_user_id   varchar(64)     references veyq_user (id),
    kind            varchar(32)     not null,                 -- TENANT_ROOT | GROUP_ROOT | PERSONAL_ROOT
    suite_id        varchar(64)     not null,
    kem_public_key  varbinary(8192) not null,
    created_at      timestamp       not null,
    sealed_name     varbinary(8192),                          -- GROUP_ROOT: sealed group name; null otherwise
    name_suite_id   varchar(64),
    constraint chk_scope_owner check (
        (kind = 'TENANT_ROOT'   and tenant_id is not null and owner_user_id is null)
        or (kind = 'GROUP_ROOT'    and tenant_id is not null and owner_user_id is null)
        or (kind = 'PERSONAL_ROOT' and tenant_id is null     and owner_user_id is not null)
    )
);
create index idx_scope_tenant on veyq_scope (tenant_id);
create unique index uq_scope_owner_personal on veyq_scope (owner_user_id, kind);
create index idx_scope_owner on veyq_scope (owner_user_id);

create table veyq_scope_grant (
    id                   varchar(64)     primary key,
    scope_id             varchar(64)     not null references veyq_scope (id),
    user_id              varchar(64)     not null references veyq_user (id),
    suite_id             varchar(64)     not null,
    wrapped_private_key  varbinary(8192) not null,            -- scope private key asym-wrapped to the user
    created_at           timestamp       not null
);
create index idx_grant_user on veyq_scope_grant (user_id);
create unique index uq_grant_scope_user on veyq_scope_grant (scope_id, user_id);

create table veyq_membership (
    id          varchar(64)  primary key,
    tenant_id   varchar(64)  not null references veyq_tenant (id),
    user_id     varchar(64)  not null references veyq_user (id),
    role        varchar(32)  not null,
    status      varchar(32)  not null,                        -- ACTIVE
    created_at  timestamp    not null
);
create index idx_membership_tenant on veyq_membership (tenant_id);

-- ── Encrypted content (per-scope content key; server stores ciphertext only) ─────────────────────────
create table veyq_scope_content_key (
    id          varchar(64)     primary key,
    scope_id    varchar(64)     not null unique references veyq_scope (id),
    suite_id    varchar(64)     not null,
    wrapped_key varbinary(8192) not null,                     -- content key asym-wrapped to the scope public key
    created_at  timestamp       not null
);

create table veyq_scope_item (
    id              varchar(64)  primary key,
    scope_id        varchar(64)  not null references veyq_scope (id),
    suite_id        varchar(64)  not null,
    sealed_content  longblob     not null,                    -- nonce || AEAD(content key, plaintext)
    created_at      timestamp    not null
);
create index idx_scope_item_scope on veyq_scope_item (scope_id);

-- The Memory tree. parent_id is NOT NULL (a root node uses the SCOPE id as its sentinel) so the unique
-- (parent_id, slug_token) enforces slug uniqueness at every level including the root. No FK on parent_id.
create table veyq_node (
    id              varchar(64)  primary key,
    scope_id        varchar(64)  not null references veyq_scope (id),
    parent_id       varchar(64)  not null,                    -- parent node id, or the scope id for a root node
    type            varchar(16)  not null,                    -- FOLDER | MEMORY | CHAPTER
    slug_token      varchar(255) not null,                    -- blinded HMAC(slugKey, normalized slug), base64url
    order_index     int          not null,
    suite_id        varchar(64)  not null,
    sealed_content  longblob     not null,                    -- nonce || AEAD(content key, name/title/metadata)
    created_at      timestamp    not null,
    cover_media_id  varchar(64),                              -- pinned cover media (nullable, no FK — handler-validated)
    constraint uq_node_parent_slug unique (parent_id, slug_token)
);
create index idx_node_scope on veyq_node (scope_id);
create index idx_node_parent on veyq_node (parent_id);

-- ── Unified media model (blob = deduplicated chunk-set of sealed frames in the BlobStore, not the DB) ──
create table veyq_blob (
    id           varchar(64)  primary key,
    scope_id     varchar(64)  not null references veyq_scope (id),
    dedup_token  varchar(255) not null,                       -- HMAC(dedupKey, SHA256(content)), base64url
    total_chunks int          not null,
    byte_size    bigint       not null,                       -- plaintext size (server-visible)
    suite_id     varchar(64)  not null,
    created_at   timestamp    not null,
    constraint uq_blob unique (scope_id, dedup_token)
);

create table veyq_blob_upload (
    id           varchar(64)  primary key,
    scope_id     varchar(64)  not null references veyq_scope (id),
    dedup_token  varchar(255) not null,
    total_chunks int          not null,
    byte_size    bigint       not null,
    status       varchar(16)  not null,                       -- OPEN | COMPLETED
    blob_id      varchar(64),                                 -- resulting content blob, set on completion
    created_by   varchar(64)  not null,
    created_at   timestamp    not null,
    staged_bytes bigint       not null default 0,             -- running total of staged chunk bytes (O(1) budget check)
    suite_id     varchar(64)                                  -- crypto suite the frames are sealed under
);
create index idx_blob_upload_scope_status on veyq_blob_upload (scope_id, status);
create index idx_blob_upload_status_created on veyq_blob_upload (status, created_at);

create table veyq_blob_chunk (
    id           varchar(64)  primary key,
    upload_id    varchar(64),                                 -- set while staging, cleared on finalize
    blob_id      varchar(64),                                 -- set on finalize (the content it belongs to)
    chunk_index  int          not null,
    object_key   varchar(256) not null,                       -- pointer into the BlobStore
    byte_size    bigint       not null,
    created_at   timestamp    not null,
    constraint uq_blob_chunk_upload unique (upload_id, chunk_index)
);
create index idx_blob_chunk_blob on veyq_blob_chunk (blob_id);

-- A media attachment hangs a content blob on a Memory/Chapter. Capture time + GPS are sealed (sealed_meta);
-- only mime_type, highlighted flag and contributor bucket are server-visible.
create table veyq_media (
    id               varchar(64)     primary key,
    scope_id         varchar(64)     not null references veyq_scope (id),
    node_id          varchar(64)     not null references veyq_node (id),
    original_blob_id varchar(64)     not null references veyq_blob (id),
    mime_type        varchar(128)    not null,                -- server-visible: image/jpeg, video/mp4, …
    created_at       timestamp       not null,
    sealed_meta      varbinary(4096) not null,                -- nonce || AEAD(content key, {takenAt, location})
    suite_id         varchar(32)     not null,
    highlighted      boolean         not null default false,  -- server-visible favourite/star flag
    created_by       varchar(255)    not null default '',     -- "user:<id>" | "share:<shareId>:<contributorId>"
    bucket_id        varchar(64)                              -- contributor bucket (nullable, no FK — handler-validated)
);
create index idx_media_node on veyq_media (node_id);
create index idx_media_scope on veyq_media (scope_id);
create index idx_media_bucket_ref on veyq_media (bucket_id);

create table veyq_media_derivative (
    id        varchar(64) primary key,
    media_id  varchar(64) not null references veyq_media (id),
    kind      varchar(32) not null,                           -- THUMBNAIL | PREVIEW
    blob_id   varchar(64) not null references veyq_blob (id),
    constraint uq_media_derivative unique (media_id, kind)
);

-- Per-contributor media buckets. owner_key/sealed bucket name; no FK on node_id (handler-validated).
create table veyq_media_bucket (
    id                varchar(64)  primary key,
    scope_id          varchar(64)  not null,
    node_id           varchar(64)  not null,
    owner_key         varchar(255) not null,                  -- "user:<id>" | "share:<shareId>:<contributorId>"
    parent_bucket_id  varchar(64),                            -- null => primary/user bucket; else a sub-bucket
    sealed_name       longblob     not null,                  -- nonce || AEAD(content key, bucket/contributor name)
    suite_id          varchar(32)  not null,
    is_primary        boolean      not null default false,
    created_at        timestamp    not null
);
create index idx_media_bucket_node on veyq_media_bucket (node_id);
create index idx_media_bucket_owner on veyq_media_bucket (node_id, owner_key);

-- ── Journal (sealed Markdown projection + operator-blind CRDT layer) ──────────────────────────────────
-- One row per node (node_id PK); no FK to veyq_node. sealed_content is nullable: a fresh CRDT journal may
-- have updates before its first compaction writes the Markdown projection.
create table veyq_memory_journal (
    node_id            varchar(64) not null primary key,
    scope_id           varchar(64) not null,
    suite_id           varchar(32) not null,
    sealed_content     longblob,                              -- nonce || AEAD(content key, markdown body); nullable
    updated_at         timestamp   not null,
    sealed_snapshot    longblob,                              -- nonce || AEAD(content key, Yjs state)
    snapshot_suite_id  varchar(32),
    base_seq           bigint      not null default 0,        -- highest seq folded into the snapshot
    next_seq           bigint      not null default 0         -- next seq to hand out for an append
);
create index veyq_memory_journal_scope_idx on veyq_memory_journal (scope_id);

-- Append-only log of sealed Yjs updates (pruned on compaction). seq is a per-node server-assigned counter.
create table veyq_memory_journal_update (
    id            varchar(64) primary key,
    node_id       varchar(64) not null,
    scope_id      varchar(64) not null,
    seq           bigint      not null,
    suite_id      varchar(32) not null,
    sealed_update longblob    not null,                       -- nonce || AEAD(content key, Yjs update)
    author_id     varchar(64) not null,
    created_at    timestamp   not null
);
create unique index veyq_journal_update_node_seq on veyq_memory_journal_update (node_id, seq);
create index veyq_journal_update_node on veyq_memory_journal_update (node_id);

-- ── Tracks, shares, playlists (sealed content, no FK to node — handler-validated) ────────────────────
create table veyq_memory_track (
    id             varchar(64) not null primary key,
    node_id        varchar(64) not null,
    scope_id       varchar(64) not null,
    suite_id       varchar(32) not null,
    sealed_content longblob    not null,                      -- nonce || AEAD(content key, {name, points})
    created_at     timestamp   not null
);
create index veyq_memory_track_node_idx on veyq_memory_track (node_id);

-- A read-only / contributor public link to a Memory subtree. The scope content key is wrapped under a
-- per-share key that rides the link fragment and never reaches the server.
create table veyq_share (
    id                  varchar(64)     not null primary key,
    scope_id            varchar(64)     not null,
    node_id             varchar(64)     not null,
    suite_id            varchar(32)     not null,
    wrapped_content_key varbinary(4096) not null,             -- nonce || AEAD(shareKey, scope content key)
    created_by          varchar(64)     not null,
    created_at          timestamp       not null,
    role                varchar(16)     not null default 'VIEWER',  -- VIEWER | CONTRIBUTOR
    password_salt       varbinary(64),                        -- set => password-protected; null => no password
    expires_at          timestamp,                            -- null => never expires
    label               varchar(120)    not null default '',  -- owner-facing link name (not shown to guests)
    notice_markdown     text                                  -- optional per-share consent terms (plaintext)
);
create index veyq_share_node_idx on veyq_share (node_id);

create table veyq_memory_playlist (
    id              varchar(64) primary key,
    scope_id        varchar(64) not null,
    node_id         varchar(64) not null,
    sealed_content  longblob    not null,                     -- nonce || AEAD(content key, {provider, url, title?, note?})
    suite_id        varchar(32) not null,
    featured        boolean     not null default false,       -- server-visible: surface one in a header
    created_at      timestamp   not null
);
create index idx_playlist_node on veyq_memory_playlist (node_id);

-- ── Groups, password reset, setup singleton ──────────────────────────────────────────────────────────
create table veyq_group_membership (
    id          varchar(64) primary key,
    scope_id    varchar(64) not null references veyq_scope (id),
    user_id     varchar(64) not null references veyq_user (id),
    role        varchar(32) not null,                         -- GroupOwner | GroupMember
    created_at  timestamp   not null
);
create unique index uq_group_member on veyq_group_membership (scope_id, user_id);
create index idx_group_member_user on veyq_group_membership (user_id);

create table veyq_password_reset (
    id           varchar(64)   not null primary key,
    user_id      varchar(64)   not null references veyq_user (id),
    token_sha256 varbinary(32) not null unique,               -- only the hash of the one-time email token
    created_at   timestamp     not null,
    expires_at   timestamp     not null,
    consumed_at  timestamp
);
create index idx_password_reset_user on veyq_password_reset (user_id);

-- Seeded singleton row, row-locked (SELECT ... FOR UPDATE) to serialize concurrent first-owner setup.
create table veyq_server_setup (
    id varchar(16) primary key
);
insert into veyq_server_setup (id) values ('SERVER');
