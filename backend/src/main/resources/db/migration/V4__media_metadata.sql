alter table lunva_media
    add column captured_at timestamp(6) null;

alter table lunva_media
    add column captured_at_override boolean not null default false;

alter table lunva_media
    add column camera_make varchar(255) null;

alter table lunva_media
    add column camera_model varchar(255) null;

alter table lunva_media
    add column lens_model varchar(255) null;

alter table lunva_media
    add column image_width int null;

alter table lunva_media
    add column image_height int null;

alter table lunva_media
    add column gps_latitude double null;

alter table lunva_media
    add column gps_longitude double null;
