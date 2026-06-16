-- P3.4 performance indexes (no schema change).

-- veyq_media lost its scope_id index when the table was recreated in V9 (only idx_media_node remained), so
-- listByScopeId / deleteByScopeId (scope open, tenant/scope purge) full-scan the table.
create index idx_media_scope on veyq_media (scope_id);

-- veyq_blob_upload had only its primary key, but two hot predicates run on it: countOpenByScope on every
-- startBlob (scope_id + status) and the hourly stale-upload sweep (status + created_at).
create index idx_blob_upload_scope_status on veyq_blob_upload (scope_id, status);
create index idx_blob_upload_status_created on veyq_blob_upload (status, created_at);

-- A running total of staged chunk bytes so the per-chunk budget check is O(1) instead of re-summing every
-- staged chunk on each PUT (which made an N-chunk upload O(N^2)). The upload row is row-locked during a PUT.
alter table veyq_blob_upload add column staged_bytes bigint not null default 0;
