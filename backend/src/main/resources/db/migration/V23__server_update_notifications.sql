create table veyq.veyq_server_update_event
(
    id           bigint auto_increment
        primary key,
    status       varchar(40)                           not null,
    from_version varchar(80)                           null,
    to_version   varchar(80)                           null,
    image        varchar(255)                          not null,
    digest       varchar(255)                          null,
    stage        varchar(40)                           null,
    occurred_at  timestamp(6)                          not null,
    created_at   timestamp default current_timestamp() not null
);

create index idx_veyq_server_update_event_occurred_at
    on veyq.veyq_server_update_event (occurred_at);

create table veyq.veyq_server_notification
(
    id              bigint auto_increment
        primary key,
    notification_type varchar(80)                          not null,
    update_event_id bigint                                null,
    created_at      timestamp default current_timestamp() not null,
    constraint fk_veyq_server_notification_update_event
        foreign key (update_event_id) references veyq.veyq_server_update_event (id)
            on delete cascade
);

create index idx_veyq_server_notification_type
    on veyq.veyq_server_notification (notification_type);

create table veyq.veyq_user_capability_ack
(
    id            bigint auto_increment
        primary key,
    user_id       bigint                                not null,
    capability_id varchar(120)                          not null,
    dismissed_at  timestamp default current_timestamp() not null,
    constraint uq_veyq_user_capability_ack
        unique (user_id, capability_id),
    constraint fk_veyq_user_capability_ack_user
        foreign key (user_id) references veyq.veyq_user (id)
            on delete cascade
);

create index idx_veyq_user_capability_ack_user_id
    on veyq.veyq_user_capability_ack (user_id);
