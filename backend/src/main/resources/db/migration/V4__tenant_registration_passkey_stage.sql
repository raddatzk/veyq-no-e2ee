create table veyq.veyq_tenant_registration_passkey_stage
(
    id          bigint auto_increment primary key,
    invite_id   bigint       not null,
    user_id     bigint       not null,
    email       varchar(255) not null,
    created_at  timestamp    not null,
    expires_at  timestamp    not null,
    consumed_at timestamp    null,
    constraint fk_veyq_tenant_registration_passkey_stage_invite
        foreign key (invite_id) references veyq.veyq_tenant_registration_invite (id)
            on delete cascade,
    constraint fk_veyq_tenant_registration_passkey_stage_user
        foreign key (user_id) references veyq.veyq_user (id)
            on delete cascade
);

create index idx_veyq_tenant_registration_passkey_stage_invite_user
    on veyq.veyq_tenant_registration_passkey_stage (invite_id, user_id);

create index idx_veyq_tenant_registration_passkey_stage_expires_at
    on veyq.veyq_tenant_registration_passkey_stage (expires_at);
