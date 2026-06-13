-- Identity & the blind key store. Every key/blob column holds opaque ciphertext + a suite id;
-- the server never interprets the bytes (see docs/wiki/crosscutting/crypto-spec.md).

create table veyq_user (
    id              varchar(64)  primary key,
    email           varchar(320) not null unique,
    display_name    varchar(255) not null,
    server_role     varchar(32)  not null,
    system_account  boolean      not null default false,
    created_at      timestamp    not null
);

create table veyq_identity_key (
    id                        varchar(64)   primary key,
    user_id                   varchar(64)   not null unique references veyq_user (id),
    suite_id                  varchar(64)   not null,
    kem_public_key            varbinary(8192) not null,
    sign_public_key           varbinary(1024) not null,
    wrapped_private_key       varbinary(8192) not null,
    wrapped_private_suite_id  varchar(64)   not null,
    created_at                timestamp     not null
);

create table veyq_key_blob (
    id             varchar(64)    primary key,
    user_id        varchar(64)    not null references veyq_user (id),
    unlock_method  varchar(32)    not null,
    suite_id       varchar(64)    not null,
    wrapped_key    varbinary(8192) not null,
    kdf_params     varbinary(2048),
    credential_ref varchar(255),
    created_at     timestamp      not null
);

create table veyq_auth_verifier (
    id          varchar(64)    primary key,
    user_id     varchar(64)    not null references veyq_user (id),
    kind        varchar(32)    not null,
    suite_id    varchar(64)    not null,
    verifier    varbinary(8192) not null,
    created_at  timestamp      not null
);
