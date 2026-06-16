-- The master-key recovery commitment: SHA-256("veyq/v1/recovery-auth" || masterKey), set on the RECOVERY
-- key blob when it is written. A password reset must present this exact value, proving the client recovered
-- the REAL master key with the Secret Key — so an email-token-only attacker can no longer reset the password
-- verifier and hijack the server account. It is not a secret (masterKey is 32 random bytes; the hash is not
-- invertible) and is never returned to clients.
alter table veyq_key_blob add column recovery_auth_tag varbinary(64);
