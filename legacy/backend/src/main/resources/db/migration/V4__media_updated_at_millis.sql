-- Media update polling resumes from a millisecond-precision cursor: the
-- afterUpdatedAt query parameter is bound to java.util.Date, which cannot carry
-- sub-millisecond precision. updated_at, however, was stored as timestamp(6) and
-- written from Instant.now() with microsecond precision. Those sub-millisecond
-- values made the keyset predicate inconsistent with "order by updated_at,
-- public_id" and could permanently skip same-millisecond updates during
-- incremental polling.
--
-- Floor existing values to milliseconds so stored precision matches the cursor
-- precision. New writes are already truncated to milliseconds by the Media
-- entity lifecycle hooks.
update veyq.veyq_media
set updated_at = timestampadd(microsecond, -mod(extract(microsecond from updated_at), 1000), updated_at)
where mod(extract(microsecond from updated_at), 1000) <> 0;
