alter table lunva_tenant add column domain varchar(255) null;

update lunva_tenant
set domain = concat(name, '.lunva.local')
where domain is null;

alter table lunva_tenant modify column domain varchar(255) not null;
alter table lunva_tenant add constraint uq_lunva_tenant_domain unique (domain);

alter table lunva_user add column password_set_at timestamp null;

update lunva_user
set password_set_at = current_timestamp
where password_set_at is null;

create table lunva_user_invitation
(
    id          bigint       not null auto_increment primary key,
    user_id     bigint       not null,
    token_hash  varchar(64)  not null unique,
    expires_at  timestamp    not null,
    accepted_at timestamp null,
    revoked_at  timestamp null,
    created_at  timestamp    not null default current_timestamp,
    constraint fk_lunva_user_invitation_user foreign key (user_id) references lunva_user (id) on delete cascade
);

create index idx_lunva_user_invitation_user_id on lunva_user_invitation (user_id);
create index idx_lunva_user_invitation_expires_at on lunva_user_invitation (expires_at);
