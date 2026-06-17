-- uc.memory.journal-live persistence (operator-blind CRDT). The journal keeps its sealed Markdown body
-- (`sealed_content`) as the read projection every reader already uses (detail view, public share) — that
-- stays. Alongside it we add the editable CRDT layer so non-overlapping sessions converge without the server
-- ever decrypting: a sealed Yjs *state* snapshot plus an append-only log of sealed Yjs *updates*.
--
-- A client opens by applying the snapshot + all updates with seq > base_seq; it appends sealed updates as it
-- edits; on compaction it writes a fresh snapshot (and the Markdown projection) covering some seq, and the
-- server prunes the folded-in updates. seq is a per-node server-assigned monotonic counter (`next_seq`).
alter table veyq_memory_journal add column sealed_snapshot longblob;          -- nonce || AEAD(contentKey, Yjs state)
alter table veyq_memory_journal add column snapshot_suite_id varchar(32);
alter table veyq_memory_journal add column base_seq bigint not null default 0;           -- highest seq folded into the snapshot
alter table veyq_memory_journal add column next_seq bigint not null default 0;           -- next seq to hand out for an append

-- `sealed_content` (Markdown) is now nullable: a fresh CRDT journal may have updates before its first compaction.
alter table veyq_memory_journal alter column sealed_content set null;

create table veyq_memory_journal_update (
    id            varchar(64)        primary key,
    node_id       varchar(64)        not null,
    scope_id      varchar(64)        not null,
    seq           bigint             not null,
    suite_id      varchar(32)        not null,
    sealed_update longblob not null,  -- nonce || AEAD(contentKey, Yjs update)
    author_id     varchar(64)        not null,
    created_at    timestamp          not null
);

create unique index veyq_journal_update_node_seq on veyq_memory_journal_update (node_id, seq);
create index veyq_journal_update_node on veyq_memory_journal_update (node_id);
