alter table lunva_tenant_member add column accepted_at timestamp null;

update lunva_tenant_member
set accepted_at = current_timestamp
where accepted_at is null;

alter table lunva_user_invitation add column tenant_id bigint null;

update lunva_user_invitation i
set tenant_id = (
    select min(tm.tenant_id)
    from lunva_tenant_member tm
    where tm.user_id = i.user_id
)
where tenant_id is null;

delete from lunva_user_invitation where tenant_id is null;

alter table lunva_user_invitation modify column tenant_id bigint not null;
alter table lunva_user_invitation
    add constraint fk_lunva_user_invitation_tenant foreign key (tenant_id) references lunva_tenant (id) on delete cascade;

create index idx_lunva_user_invitation_tenant_id on lunva_user_invitation (tenant_id);
