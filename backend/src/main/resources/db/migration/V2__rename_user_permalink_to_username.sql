alter table veyq_user rename column permalink to username;
alter table veyq_user rename constraint uq_veyq_user_permalink to uq_veyq_user_username;
