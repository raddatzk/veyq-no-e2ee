-- Per-contributor media buckets (ADR 0023). Each medium records WHO contributed it (created_by: "user:<id>"
-- for members, "share:<shareId>:<contributorId>" for guests) and WHICH bucket it sits in (bucket_id; null =
-- the contributor's primary bucket). Bucket names are sealed user content (operator-blind), like node names.
alter table veyq_media add column created_by varchar(255) not null default '';
alter table veyq_media add column bucket_id varchar(64);

-- Backfill existing media to the scope owner's member key so they appear under that contributor's bucket.
update veyq_media
   set created_by = concat('user:', (select owner_user_id from veyq_scope where veyq_scope.id = veyq_media.scope_id))
 where created_by = ''
   and (select owner_user_id from veyq_scope where veyq_scope.id = veyq_media.scope_id) is not null;

create table veyq_media_bucket (
    id                varchar(64)  primary key,
    scope_id          varchar(64)  not null,
    node_id           varchar(64)  not null,
    owner_key         varchar(255) not null,         -- contributor key: "user:<id>" or "share:<shareId>:<contributorId>"
    parent_bucket_id  varchar(64),                   -- null => primary/user bucket; else a sub-bucket
    sealed_name       longblob     not null,         -- nonce || AEAD(content key, bucket/contributor name)
    suite_id          varchar(32)  not null,
    is_primary        boolean      not null default false,
    created_at        timestamp    not null
);
create index idx_media_bucket_node on veyq_media_bucket (node_id);
create index idx_media_bucket_owner on veyq_media_bucket (node_id, owner_key);
create index idx_media_bucket_ref on veyq_media (bucket_id);
