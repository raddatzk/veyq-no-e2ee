alter table veyq.veyq_reset_password_token
    add column reset_domain varchar(255) null after user_id;

create index idx_veyq_reset_password_token_reset_domain
    on veyq.veyq_reset_password_token (reset_domain);
