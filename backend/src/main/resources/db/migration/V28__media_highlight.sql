-- A server-visible "highlight" (favourite/star) flag per medium. It is benign metadata — not content — so it
-- stays plaintext, enabling a filterable Highlights view. Capture time + location remain sealed (sealedMeta).
alter table veyq_media add column highlighted boolean not null default false;
