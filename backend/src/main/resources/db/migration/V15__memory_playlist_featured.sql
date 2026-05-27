alter table veyq.veyq_memory_playlist
    add column featured boolean default false not null;

create index idx_veyq_memory_playlist_featured
    on veyq.veyq_memory_playlist (memory_id, featured, created_at, id);
