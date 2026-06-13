-- Opaque server sessions issued at login. The token authenticates the user to the server; it grants
-- no content access on its own (auth != unlock — the server holds no keys).

create table veyq_session (
    id          varchar(64)  primary key,
    token       varchar(128) not null unique,
    user_id     varchar(64)  not null references veyq_user (id),
    created_at  timestamp    not null,
    expires_at  timestamp    not null
);

create index idx_session_user on veyq_session (user_id);
