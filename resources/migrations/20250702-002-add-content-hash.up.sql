ALTER TABLE grout_media ADD COLUMN content_hash text;
--;;
-- SHA-256 of the original source bytes. Nullable for pre-hash rows; Postgres
-- allows multiple NULLs under a unique index. The dedup lookup considers
-- superseded rows too (so re-intake can revive), hence a global unique index.
CREATE UNIQUE INDEX grout_media_content_hash ON grout_media (content_hash);
--;;
