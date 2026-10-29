DROP INDEX IF EXISTS grout_media_unenriched_idx;
--;;

ALTER TABLE grout_media
  DROP COLUMN enrich_attempted_at;
