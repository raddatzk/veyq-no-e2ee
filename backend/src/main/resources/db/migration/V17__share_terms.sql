-- uc.share.terms — consent notices a guest must accept before viewing/contributing through a share.
-- Tenant-level Terms of Service (set by a Tenant Owner) apply to EVERY share of that tenant's memories;
-- terms_version bumps on each change so guests re-accept. A per-share notice adds extra terms on one link.
-- These are low-sensitivity policy text (no memory content) and server-served, so they're stored plaintext.
alter table veyq_tenant add column terms_markdown text;
alter table veyq_tenant add column terms_version integer not null default 0;
alter table veyq_share add column notice_markdown text;
