alter table veyq.veyq_media add column
(
    updated_at timestamp(6) default current_timestamp() not null,
    highlight boolean default false not null
);

create index idx_veyq_media_updated_at
    on veyq.veyq_media (memory_id, updated_at, public_id);