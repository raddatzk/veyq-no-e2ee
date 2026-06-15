-- Groups: a tenant can hold many GROUP_ROOT scopes (collaborative sub-spaces for a subset of its members)
-- alongside its single TENANT_ROOT. A group name is sealed under the group content key (operator-blind).
-- Group membership carries its own roles (GroupOwner | GroupMember); access still = holding the scope grant.
-- Greenfield: destructive index/constraint changes are fine.

-- A tenant now has more than one scope (root + groups), so the plain "one scope per tenant" unique index
-- can't stand; "one TENANT_ROOT per tenant" is enforced in application code instead.
drop index uq_scope_tenant;

alter table veyq_scope drop constraint chk_scope_owner;
alter table veyq_scope add constraint chk_scope_owner check (
    (kind = 'TENANT_ROOT' and tenant_id is not null and owner_user_id is null)
    or (kind = 'GROUP_ROOT' and tenant_id is not null and owner_user_id is null)
    or (kind = 'PERSONAL_ROOT' and tenant_id is null and owner_user_id is not null)
);

-- the sealed group name (ciphertext under the group content key); null for tenant/personal scopes
alter table veyq_scope add column sealed_name varbinary(8192);
alter table veyq_scope add column name_suite_id varchar(64);

create table veyq_group_membership (
    id          varchar(64) primary key,
    scope_id    varchar(64) not null references veyq_scope (id),
    user_id     varchar(64) not null references veyq_user (id),
    role        varchar(32) not null,  -- GroupOwner | GroupMember
    created_at  timestamp   not null
);
create unique index uq_group_member on veyq_group_membership (scope_id, user_id);
create index idx_group_member_user on veyq_group_membership (user_id);
