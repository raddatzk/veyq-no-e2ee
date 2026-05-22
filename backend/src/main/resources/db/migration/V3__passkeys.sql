create table veyq.veyq_passkey_credential
(
    id                   bigint auto_increment
        primary key,
    user_id              bigint                                not null,
    credential_id        varchar(512)                          not null,
    public_key           tinyblob                                  not null,
    public_key_algorithm bigint                                not null,
    counter              bigint                                not null,
    aaguid               varchar(36)                           not null,
    transports           varchar(255)                          null,
    display_name         varchar(255)                          not null,
    created_at           timestamp default current_timestamp() not null,
    last_used_at         timestamp                             null,
    constraint uq_veyq_passkey_credential_id
        unique (credential_id),
    constraint fk_veyq_passkey_credential_user
        foreign key (user_id) references veyq.veyq_user (id)
            on delete cascade
);

create index idx_veyq_passkey_credential_user_id
    on veyq.veyq_passkey_credential (user_id);
