-- uc.account.change-email — one-time tokens for confirming an account email change.
-- Mirrors veyq_password_reset: only the SHA-256 of the token is stored (the raw token rides the email link to
-- the NEW address). new_email holds the address the change is confirming to. Single-use via confirmed_at.
create table veyq_email_change_token (
    id            varchar(64)   not null primary key,
    user_id       varchar(64)   not null references veyq_user (id),
    new_email     varchar(320)  not null,                       -- the address the change confirms to
    token_sha256  varbinary(32) not null unique,                -- only the hash of the one-time email token
    created_at    timestamp     not null,
    expires_at    timestamp     not null,
    confirmed_at  timestamp
);
