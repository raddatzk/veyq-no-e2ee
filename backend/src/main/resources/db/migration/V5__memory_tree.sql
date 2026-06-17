-- uc.memory.create-tree — the encrypted Memory tree (AD-26 blinded slug tokens). A node is a Folder,
-- Memory or Chapter under a scope; the server holds only the ID graph, the structural type, an opaque
-- blinded slug token, an ordering index and the sealed name/metadata. It never sees names or paths.
--
-- parent_id is NOT NULL: a top-level node uses the SCOPE id as its root sentinel, so UNIQUE(parent_id,
-- slug_token) enforces slug uniqueness at every level INCLUDING the root (a nullable parent would let
-- two roots collide, since NULL != NULL in a unique index). There is no DB FK on parent_id — the edge
-- is validated in the handler (same-scope, containment invariants); scope_id keeps its FK.

create table veyq_node (
    id              varchar(64)        primary key,
    scope_id        varchar(64)        not null references veyq_scope (id),
    parent_id       varchar(64)        not null,  -- parent node id, or the scope id for a root-level node
    type            varchar(16)        not null,  -- FOLDER | MEMORY | CHAPTER
    slug_token      varchar(255)       not null,  -- blinded: HMAC(slugKey, normalized slug), base64url
    order_index     int                not null,
    suite_id        varchar(64)        not null,
    sealed_content  longblob not null,  -- nonce || AEAD(content key, name/title/metadata)
    created_at      timestamp          not null,
    constraint uq_node_parent_slug unique (parent_id, slug_token)
);

create index idx_node_scope on veyq_node (scope_id);
create index idx_node_parent on veyq_node (parent_id);
