ALTER TABLE grout_media
  ADD COLUMN enrichment_context          jsonb,
  ADD COLUMN enrichment_grounding_source text;
--;;

COMMENT ON COLUMN grout_media.enrichment_context IS
  'Last MediaContext returned by Tunabrain on the most recent enrichment.
   Replayed on retry so the operator can correct a bad Wikipedia match by
   editing the summary; subsequent calls re-tag against the fix.
   NULL until the first enrichment completes.';
--;;

COMMENT ON COLUMN grout_media.enrichment_grounding_source IS
  'Source of the summary used to ground the most recent enrichment:
   provided-text, provided-summary, provided-link, wikipedia, or none.
   Diagnostic for "did the auto-search land right?"';
--;;
