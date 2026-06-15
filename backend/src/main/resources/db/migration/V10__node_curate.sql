-- uc.memory.curate — a Memory may pin one of its media as its cover. Nullable; no FK (validated in the
-- handler, like parent_id) so a later media delete just leaves a dangling id the client ignores.
alter table veyq_node add column cover_media_id varchar(64);
