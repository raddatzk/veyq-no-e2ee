create table lunva_user
(
    id            bigint       not null auto_increment primary key,
    username      varchar(120) not null unique,
    password_hash varchar(255) not null,
    display_name  varchar(255) not null,
    email         varchar(255) not null,
    created_at    timestamp    not null default current_timestamp
);

create table lunva_role
(
    id        bigint       not null auto_increment primary key,
    role_name varchar(120) not null unique
);

create table lunva_user_role
(
    user_id bigint not null,
    role_id bigint not null,
    primary key (user_id, role_id),
    constraint fk_lunva_user_role_user foreign key (user_id) references lunva_user (id) on delete cascade,
    constraint fk_lunva_user_role_role foreign key (role_id) references lunva_role (id) on delete cascade
);

create table lunva_refresh_token
(
    id               bigint      not null auto_increment primary key,
    token_hash       varchar(64) not null unique,
    user_id          bigint      not null,
    expires_at       timestamp   not null,
    revoked_at       timestamp null,
    created_at       timestamp   not null default current_timestamp,
    last_accessed_at timestamp   not null default current_timestamp,
    rotated_to       bigint null,
    constraint fk_lunva_refresh_token_user foreign key (user_id) references lunva_user (id) on delete cascade,
    constraint fk_lunva_refresh_token_rotated_to foreign key (rotated_to) references lunva_refresh_token (id)
);

create table lunva_access_token
(
    id               bigint      not null auto_increment primary key,
    token_hash       varchar(64) not null unique,
    user_id          bigint      not null,
    expires_at       timestamp   not null,
    revoked_at       timestamp null,
    created_at       timestamp   not null default current_timestamp,
    last_accessed_at timestamp   not null default current_timestamp,
    refresh_token_id bigint      not null,
    constraint fk_lunva_access_token_user foreign key (user_id) references lunva_user (id) on delete cascade,
    constraint fk_lunva_access_token_refresh_token foreign key (refresh_token_id) references lunva_refresh_token (id)
);

create index idx_lunva_access_token_user_id on lunva_access_token (user_id);
create index idx_lunva_access_token_expires_at on lunva_access_token (expires_at);
create index idx_lunva_access_token_revoked_at on lunva_access_token (revoked_at);
create index idx_lunva_refresh_token_user_id on lunva_refresh_token (user_id);
create index idx_lunva_refresh_token_expires_at on lunva_refresh_token (expires_at);
create index idx_lunva_refresh_token_revoked_at on lunva_refresh_token (revoked_at);

create table lunva_config
(
    id           bigint       not null auto_increment primary key,
    config_key   varchar(120) not null unique,
    config_value varchar(255) not null,
    created_at   timestamp    not null default current_timestamp,
    updated_at   timestamp    not null default current_timestamp on update current_timestamp
);

insert into lunva_user(username, password_hash, display_name, email)
values ('admin', '$2y$12$RoRZriBg6xL4yVHugf345eDuNiPNnpe0EOHzAEk4c8k0PyzVC.80O', 'Administrator', 'kevin@raddatz.me');

insert into lunva_role(role_name)
values ('admin');

insert into lunva_user_role(user_id, role_id)
select u.id, r.id
from lunva_user u
         join lunva_role r on r.role_name = 'admin'
where u.username = 'admin';
