create table lunva_user_email_change
(
    id            bigint       not null auto_increment primary key,
    user_id       bigint       not null,
    pending_email varchar(255) not null,
    token_hash    varchar(64)  not null unique,
    expires_at    timestamp    not null,
    accepted_at   timestamp null,
    revoked_at    timestamp null,
    created_at    timestamp    not null default current_timestamp,
    constraint fk_lunva_user_email_change_user foreign key (user_id) references lunva_user (id) on delete cascade
);

create index idx_lunva_user_email_change_user_id on lunva_user_email_change (user_id);
create index idx_lunva_user_email_change_expires_at on lunva_user_email_change (expires_at);
create index idx_lunva_user_email_change_pending_email on lunva_user_email_change (pending_email);
