-- uc.memory.playlist (ADR 0010, revised operator-blind) — a Memory/Chapter may link streaming playlists. The
-- payload {provider, url, title, note} is SEALED under the scope content key (the server stores ciphertext and
-- is blind to the link/title, like tracks/journal). Only the benign `featured` flag is server-visible (so a
-- header can surface one without decryption). Many per node; at most one featured (enforced in the handler).
create table veyq_memory_playlist (
    id              varchar(64)  primary key,
    scope_id        varchar(64)  not null,
    node_id         varchar(64)  not null,
    sealed_content  longblob     not null,   -- nonce || AEAD(content key, {provider, url, title?, note?})
    suite_id        varchar(32)  not null,
    featured        boolean      not null default false,
    created_at      timestamp    not null
);
create index idx_playlist_node on veyq_memory_playlist (node_id);
