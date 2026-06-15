-- Password reset: a forgotten password is recoverable with the Secret Key (Emergency Kit) AND email access.
-- The server emails a one-time token (only its sha256 is stored); presenting the token releases the RECOVERY
-- master-key wrap and authorizes swapping the PASSWORD wrap + auth verifier. The token is the email factor;
-- the Secret Key is the crypto factor (the blind server never sees either secret).
create table veyq_password_reset (
    id          varchar(64)  not null primary key,
    user_id     varchar(64)  not null references veyq_user (id),
    token_sha256 varbinary(32) not null unique,
    created_at  timestamp    not null,
    expires_at  timestamp    not null,
    consumed_at timestamp
);

create index idx_password_reset_user on veyq_password_reset (user_id);
