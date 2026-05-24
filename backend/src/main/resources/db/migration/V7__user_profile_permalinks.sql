alter table veyq.veyq_user
    add column permalink varchar(120) null;

update veyq.veyq_user
set permalink = concat('user-', substring(replace(uuid(), '-', ''), 1, 8));

alter table veyq.veyq_user
    modify column permalink varchar(120) not null;

create unique index uq_veyq_user_permalink
    on veyq.veyq_user (permalink);
