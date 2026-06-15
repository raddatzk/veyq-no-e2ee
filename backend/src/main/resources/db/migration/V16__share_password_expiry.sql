-- uc.memory.share — optional hardening: a password and/or an expiry on a share link.
-- password_salt: when set, the share is password-protected — the per-share key is combined client-side with
--   Argon2id(password, salt) to derive the unwrap key, so the link ALONE cannot decrypt (the server never
--   sees the password; it stores only the salt). NULL = no password.
-- expires_at: after this instant the public endpoints deny access (the owner can still see/revoke). NULL = never.
alter table veyq_share add column password_salt varbinary(64);
alter table veyq_share add column expires_at timestamp;
-- label: an optional owner-facing name for the link ("For grandma") so the Active-links list is manageable.
-- It is NOT shown to guests and is low-sensitivity (no content), so it's stored plaintext.
alter table veyq_share add column label varchar(120) not null default '';
