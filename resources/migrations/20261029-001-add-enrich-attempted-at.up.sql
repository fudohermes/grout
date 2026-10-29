ALTER TABLE grout_media
  ADD COLUMN enrich_attempted_at timestamptz;
--;;

COMMENT ON COLUMN grout_media.enrich_attempted_at IS
  'When the per-file enrichment sweep last tried this row (success or not).
   The sweep picks never-tried rows first, then the least recently tried, so
   a row Tunabrain keeps failing on rotates to the back of the queue instead
   of being re-picked every sweep and starving the rows behind it.';
--;;

CREATE INDEX grout_media_unenriched_idx ON grout_media (enrich_attempted_at NULLS FIRST, created_at)
  WHERE enriched = false AND superseded_at IS NULL;
