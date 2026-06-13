-- First encrypted content under a scope (AD-03 per-scope content key). The content key is a stable
-- symmetric key wrapped to the scope public key; items are AEAD-sealed under it. The server is blind:
-- it stores only ciphertext. The content key survives keypair rotation (rotation re-wraps it later).

create table veyq_scope_content_key (
    id          varchar(64)     primary key,
    scope_id    varchar(64)     not null unique references veyq_scope (id),
    suite_id    varchar(64)     not null,
    wrapped_key varbinary(8192) not null,  -- content key asym-wrapped to the scope public key
    created_at  timestamp       not null
);

create table veyq_scope_item (
    id              varchar(64)      primary key,
    scope_id        varchar(64)      not null references veyq_scope (id),
    suite_id        varchar(64)      not null,
    sealed_content  varbinary(1048576) not null,  -- nonce || AEAD(content key, plaintext)
    created_at      timestamp        not null
);

create index idx_scope_item_scope on veyq_scope_item (scope_id);
