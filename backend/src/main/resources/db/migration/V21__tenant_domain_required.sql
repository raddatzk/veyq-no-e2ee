-- Tenants are domain-scoped: the request host resolves which tenant a (prefix-less) member URL belongs to,
-- so a domain is required and unique per tenant. Greenfield: enforce it at the schema level.
alter table veyq_tenant alter column domain set not null;
alter table veyq_tenant add constraint uq_tenant_domain unique (domain);
