-- Grace-period tenant deletion: deleting a tenant schedules it (stays fully usable until the deadline so
-- members can secure data), then an hourly GC hard-purges it. A null scheduled_deletion_at = not scheduled;
-- the owner can cancel before the deadline by clearing it.
alter table veyq_tenant add column scheduled_deletion_at timestamp;
