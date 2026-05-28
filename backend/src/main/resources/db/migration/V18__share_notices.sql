alter table veyq.veyq_tenant
    add column terms_markdown_by_locale text null;

alter table veyq.veyq_tenant
    add column terms_version int default 0 not null;

alter table veyq.veyq_memory_share
    add column notice_markdown_by_locale text null;

alter table veyq.veyq_memory_share
    add column notice_version int default 0 not null;
