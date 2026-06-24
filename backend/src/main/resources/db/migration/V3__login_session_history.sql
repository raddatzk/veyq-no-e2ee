alter table veyq_session add column root_session_id varchar(64);
alter table veyq_session add column parent_session_id varchar(64);
alter table veyq_session add column encrypted_label text;
alter table veyq_session add column login_method varchar(64);
alter table veyq_session add column revoked_at timestamp;
alter table veyq_session add column revoked_reason varchar(64);

update veyq_session
set root_session_id = id,
    encrypted_label = device_name,
    login_method = 'PASSWORD'
where root_session_id is null;

create index idx_session_user_root on veyq_session (user_id, root_session_id);
create index idx_session_parent on veyq_session (parent_session_id);
create index idx_session_revoked_at on veyq_session (revoked_at);
