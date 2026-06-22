-- Device/login name for unlock wraps. Set for DEVICE wraps (the device name a user gives at login,
-- shown and removable in user settings); null for PASSWORD/RECOVERY/PASSKEY.
alter table veyq_key_blob add column name varchar(255);
