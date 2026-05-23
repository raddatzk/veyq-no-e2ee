alter table veyq.veyq_memory
    add column start_date date null;

alter table veyq.veyq_memory
    add column end_date date null;

update veyq.veyq_memory memory
set start_date = coalesce(
        (
            select cast(min(coalesce(media.captured_at, media.created_at)) as date)
            from veyq.veyq_media media
            where media.memory_id = memory.id
        ),
        cast(memory.created_at as date)
    ),
    end_date = coalesce(
        (
            select cast(max(coalesce(media.captured_at, media.created_at)) as date)
            from veyq.veyq_media media
            where media.memory_id = memory.id
        ),
        cast(memory.created_at as date)
    );

alter table veyq.veyq_memory
    modify column start_date date not null;

alter table veyq.veyq_memory
    modify column end_date date not null;
