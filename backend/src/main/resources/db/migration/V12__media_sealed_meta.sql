-- uc.media — seal per-media sensitive metadata (capture time + GPS location) under the scope content key
-- instead of storing a server-visible taken_at. The server is now blind to when/where media was captured;
-- the client decrypts sealed_meta to order, bucket by date, and place map pins. mime_type stays plaintext.
-- Greenfield: destructive (any existing rows lose their plaintext taken_at — no production data to preserve).
-- The table is empty in every environment (greenfield), so the NOT NULL columns need no backfill default.
alter table veyq_media drop column taken_at;
alter table veyq_media add column sealed_meta varbinary(4096) not null;
alter table veyq_media add column suite_id varchar(32) not null;
