create table veyq.veyq_tenant_registration_invite
(
    id                 bigint auto_increment primary key,
    tenant_id          bigint       not null,
    created_by_user_id bigint       not null,
    token_hash         varchar(255) not null,
    token              varchar(255) not null,
    expires_at         timestamp    not null,
    revoked_at         timestamp    null,
    max_registrations  int          null,
    registration_count int          not null default 0,
    created_at         timestamp    not null,
    constraint uq_veyq_tenant_registration_invite_token_hash
        unique (token_hash),
    constraint uq_veyq_tenant_registration_invite_token
        unique (token),
    constraint fk_veyq_tenant_registration_invite_tenant
        foreign key (tenant_id) references veyq.veyq_tenant (id),
    constraint fk_veyq_tenant_registration_invite_created_by
        foreign key (created_by_user_id) references veyq.veyq_user (id)
);

create index idx_veyq_tenant_registration_invite_tenant_id
    on veyq.veyq_tenant_registration_invite (tenant_id);
