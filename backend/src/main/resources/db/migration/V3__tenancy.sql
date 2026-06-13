-- Tenancy + scope provisioning. The tenant shell + invite are plaintext metadata; scope key material
-- is opaque (the scope private key is only ever stored wrapped to a user — the server holds no key).

create table veyq_tenant (
    id            varchar(64)  primary key,
    name          varchar(255) not null unique,
    display_name  varchar(255) not null,
    domain        varchar(255),
    status        varchar(32)  not null,   -- PENDING, ACTIVE
    created_by    varchar(64)  not null references veyq_user (id),
    created_at    timestamp    not null
);

create table veyq_tenant_invite (
    id           varchar(64)    primary key,
    tenant_id    varchar(64)    not null references veyq_tenant (id),
    role         varchar(32)    not null,
    secret_hash  varbinary(64)  not null,  -- SHA-256 of the one-time bootstrap secret
    status       varchar(32)    not null,  -- PENDING, CONSUMED, REVOKED
    kind         varchar(16)    not null default 'OWNER',  -- OWNER (mint new scope) | MEMBER (temp-wrap)
    scope_id     varchar(64),   -- MEMBER: the granted scope (veyq_scope.id; FK omitted, table defined later)
    temp_public_key                varbinary(2048),  -- MEMBER: onboarding temp keypair public key
    temp_wrapped_scope_private_key varbinary(8192),  -- MEMBER: scope private key wrapped to the temp public key
    temp_wrap_suite_id             varchar(64),
    consumed_by  varchar(64)    references veyq_user (id),
    created_by   varchar(64)    not null references veyq_user (id),
    created_at   timestamp      not null,
    expires_at   timestamp      not null
);

create table veyq_scope (
    id              varchar(64)     primary key,
    tenant_id       varchar(64)     references veyq_tenant (id),
    owner_user_id   varchar(64)     references veyq_user (id),
    kind            varchar(32)     not null,  -- TENANT_ROOT, PERSONAL_ROOT
    suite_id        varchar(64)     not null,
    kem_public_key  varbinary(8192) not null,
    created_at      timestamp       not null,
    constraint chk_scope_owner
        check (
            (kind = 'TENANT_ROOT' and tenant_id is not null and owner_user_id is null)
            or
            (kind = 'PERSONAL_ROOT' and tenant_id is null and owner_user_id is not null)
        )
);

create table veyq_scope_grant (
    id                   varchar(64)     primary key,
    scope_id             varchar(64)     not null references veyq_scope (id),
    user_id              varchar(64)     not null references veyq_user (id),
    suite_id             varchar(64)     not null,
    wrapped_private_key  varbinary(8192) not null,  -- scope private key asym-wrapped to the user's identity key
    created_at           timestamp       not null
);

create table veyq_membership (
    id          varchar(64)  primary key,
    tenant_id   varchar(64)  not null references veyq_tenant (id),
    user_id     varchar(64)  not null references veyq_user (id),
    role        varchar(32)  not null,
    status      varchar(32)  not null,  -- ACTIVE
    created_at  timestamp    not null
);

-- one TENANT_ROOT scope per tenant (personal scopes have a null tenant_id; H2 allows multiple nulls)
create unique index uq_scope_tenant on veyq_scope (tenant_id);
create unique index uq_scope_owner_personal on veyq_scope (owner_user_id, kind);
create index idx_scope_owner on veyq_scope (owner_user_id);
create index idx_grant_user on veyq_scope_grant (user_id);
create unique index uq_grant_scope_user on veyq_scope_grant (scope_id, user_id);
create index idx_membership_tenant on veyq_membership (tenant_id);
