-- A4 — single-Server-Owner setup is a TOCTOU: createServerOwner reads "no owner yet" then inserts, so two
-- concurrent unauthenticated POSTs could both create an owner. This seeded singleton row is row-locked
-- (SELECT ... FOR UPDATE) at the start of the setup transaction, serializing concurrent setups; the loser
-- then sees the owner already exists and gets a clean 409.
CREATE TABLE veyq_server_setup (
    id VARCHAR(16) PRIMARY KEY
);
INSERT INTO veyq_server_setup (id) VALUES ('SERVER');
