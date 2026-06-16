-- Store only the SHA-256 of the bearer token, never the raw token, so a DB dump yields no usable
-- credential. Existing rows hold raw tokens that can no longer be matched (the server now looks up by
-- hash), so clear them — every active session must re-authenticate once. Greenfield: destructive is fine.
delete from veyq_session;

alter table veyq_session rename column token to token_hash;
