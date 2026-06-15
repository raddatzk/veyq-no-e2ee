-- uc.memory.share — a share carries a role: VIEWER (read-only) or CONTRIBUTOR (may also add media + write
-- the journal, but cannot delete/rename/move/re-share). Existing rows default to the safe read-only role.
alter table veyq_share add column role varchar(16) not null default 'VIEWER';
