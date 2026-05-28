alter table veyq.veyq_passkey_credential
    add column relying_party_id varchar(255) null after display_name;

create index idx_veyq_passkey_credential_rp_id
    on veyq.veyq_passkey_credential (relying_party_id);

create table veyq.veyq_passkey_link_token
(
    id               bigint auto_increment
        primary key,
    code_hash        varchar(64)                           not null,
    grant_hash       varchar(64)                           null,
    user_id          bigint                                not null,
    auth_domain      varchar(255)                          not null,
    target_domain    varchar(255)                          not null,
    redirect_path    varchar(1024)                         not null,
    expires_at       timestamp                             not null,
    exchanged_at     timestamp                             null,
    consumed_at      timestamp                             null,
    created_at       timestamp default current_timestamp() not null,
    constraint uq_veyq_passkey_link_code_hash
        unique (code_hash),
    constraint uq_veyq_passkey_link_grant_hash
        unique (grant_hash),
    constraint fk_veyq_passkey_link_token_user
        foreign key (user_id) references veyq.veyq_user (id)
            on delete cascade
);

create index idx_veyq_passkey_link_token_user_id
    on veyq.veyq_passkey_link_token (user_id);

create index idx_veyq_passkey_link_token_target_domain
    on veyq.veyq_passkey_link_token (target_domain);
