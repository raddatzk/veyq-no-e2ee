create table lunva_reset_password_token
(
    id          bigint       not null auto_increment primary key,
    user_id     bigint       not null,
    token_hash  varchar(64)  not null unique,
    expires_at  timestamp    not null,
    accepted_at timestamp null,
    revoked_at  timestamp null,
    created_at  timestamp    not null default current_timestamp,
    constraint fk_lunva_reset_password_token_user foreign key (user_id) references lunva_user (id) on delete cascade
);

create index idx_lunva_reset_password_token_user_id on lunva_reset_password_token (user_id);
create index idx_lunva_reset_password_token_expires_at on lunva_reset_password_token (expires_at);