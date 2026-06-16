-- P4.2 — record the crypto suite an upload's frames are sealed under, so the finalized blob and the
-- chunk-read response carry the real suite instead of a hardcoded "v1" (latent multi-suite bug).
ALTER TABLE veyq_blob_upload ADD COLUMN suite_id VARCHAR(64);
