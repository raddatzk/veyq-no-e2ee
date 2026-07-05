-- A group scope carries a member-readable name, sealed under its own scope key K_SC (en.spaces.group-scope,
-- cr-0032, ADR 0014). Stored as opaque base64 ciphertext on the scope row: the server sees an UNNAMED group
-- and never the plaintext. Nullable — TENANT/PERSONAL scopes are anonymous and carry no per-scope name.
ALTER TABLE veyq_scope ADD COLUMN encrypted_name TEXT NULL;
