create index idx_veyq_media_archive_memory_sort
    on veyq.veyq_media (memory_id, captured_at, created_at, id);
