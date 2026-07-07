(ns grout.media.enrich
  "Enrichment orchestration (GROUT.md §9): read a row, ask Tunabrain for
  structured dimensions and free-form tags, merge, and persist with
  `enriched=true`.

  Per the design at `/opt/data/home/docs/grout-tunabrain-enrichment-requirements.md`
  and the no-generic-chat-endpoints principle for Tunabrain, Grout talks
  to Tunabrain via two typed endpoints:

    1. `POST /categorize` — structured dimensions (audience, channel, ...).
       The orchestrator supplies the dimension `categories` map; Tunabrain
       picks `values` for each from the allowed set.

    2. `POST /tags` — free-form tags. The orchestrator supplies the row's
       current tags as `existing_tags`; Tunabrain reuses them when possible
       and adds new ones.

  Both endpoints return a `MediaContext` object that the orchestrator
  persists verbatim into `enrichment_context`. The next enrichment
  attempt on the same row replays this context so a human-corrected
  `summary` flows back to the model (per the `MediaContext` doc: *\"Store
  the returned context in Tunarr Scheduler and edit it in a UI; sending
  the corrected context back on the next request re-tags against the
  fix.\"*).

  AI does **not** set `name` or `description` — those stay human-only.
  The AI's job is classification (dimensions + tags), not naming. See
  the design doc §3.1 for the rationale."
  (:require [clojure.string :as str]
            [grout.media.store :as store]
            [grout.tunabrain :as tb]
            [taoensso.timbre :as log]))

(defn- union-tags
  "Concatenate `existing` and `incoming` tag vectors, drop blanks,
  dedup (preserve first-seen order). Returns a vector of strings."
  [existing incoming]
  (->> (concat (vec (or existing [])) (vec (or incoming [])))
       (map str)
       (map str/trim)
       (remove str/blank?)
       distinct
       vec))

(defn merge-enrichment
  "Merge an existing row with the AI response from `request-categorization!`
  + `request-tags!`. The result is the payload to write via
  `store/set-enriched!`:

    * `:tags` — union of row's existing tags and the AI-derived tags
      (incl. the dimension-as-tag prefix expansion). Human-set tags
      are preserved.
    * `:enrichment-context` — the last `MediaContext` returned by
      Tunabrain (categorize or tags, whichever ran second), for
      replay on the next attempt.
    * `:enrichment-grounding-source` — the `context.source` value
      (provided-text / provided-summary / provided-link / wikipedia /
      none). Diagnostic only.

  AI does not set `:name` or `:description`; those stay human-only."
  [row categorize-resp tags-resp]
  (let [existing-tags  (vec (:tags row))
        dim-tag-prefix (tb/dimension-selections->tag-prefix
                         (:dimensions categorize-resp))
        new-tags       (:tags tags-resp)
        merged-tags    (union-tags existing-tags
                                     (concat dim-tag-prefix new-tags))
        ;; Use the last context returned (tags-resp ran second; if its
        ;; context is missing, fall back to categorize's). This is the
        ;; grounding the next call should replay.
        last-context   (or (:context tags-resp) (:context categorize-resp))]
    {:tags                       merged-tags
     :enrichment-context         last-context
     :enrichment-grounding-source (or (:source last-context) "none")}))

(defn- with-existing-context
  "Pick the right `MediaContext` to send to Tunabrain on a new attempt.

  Priority:
    1. The context stored in the row from the last enrichment
       (`enrichment_context`) — this is what was last echoed back by
       Tunabrain, possibly corrected by a human in the UI.
    2. nil — first attempt; Tunabrain will use its default
       Wikipedia/auto-search grounding (which is wrong for short-form
       content, but we send the human-set `name` as `title` and let
       Tunabrain's category validation work).

  Returns nil if the row has no stored context (caller passes nil to
  `request-categorization!`/`request-tags!` to opt out)."
  [row]
  (when-let [ctx (:enrichment_context row)]
    (when (map? ctx)
      ctx)))

(defn enrich-one!
  "Enrich a single media row via Tunabrain. Returns the updated row, or
  nil when:
    * the row is missing or superseded (and `include-superseded?` is false), or
    * both Tunabrain calls succeed but produce no usable dimensions
      AND no new tags (i.e. the AI had nothing to add — we leave the
      row as `enriched=false` so a later sweep can retry after a UI
      correction).

  Tunabrain HTTP errors are caught and logged; the row is left
  `enriched=false` for the next sweep.

  Arguments:
    ds              — a `javax.sql.DataSource`
    tunabrain       — a `TunabrainClient` (from `grout.tunabrain/create`)
    dim-config      — a map of `dimension-name -> {:description ... :values [...]}`
                      for the `/categorize` call. Built by the system
                      layer from a static config (audience) plus the
                      dynamic channel catalog fetched from Tunarr Scheduler.
    id              — the row id to enrich."
  [ds tunabrain dim-config id]
  (when-let [row (store/find-by-id ds id {:include-superseded? true})]
    (let [ctx (with-existing-context row)]
      (try
        (let [cat-resp (tb/request-categorization! tunabrain row dim-config
                                                    :context ctx)
              tag-resp (tb/request-tags! tunabrain row (:tags row)
                                         :context (:context cat-resp))
              merged   (merge-enrichment row cat-resp tag-resp)
              ;; Only flip enriched=true if the AI actually contributed
              ;; something (a dimension or a new tag). If the model
              ;; returned nothing, the row stays enriched=false so a
              ;; later sweep can retry.
              nothing-new? (and (empty? (:dimensions cat-resp))
                                (empty? (:tags tag-resp)))]
          (if nothing-new?
            (do (log/warn "No enrichment produced; leaving enriched=false"
                          {:id id})
                nil)
            (do (log/info "Enriched media"
                          {:id id
                           :dimensions (count (:dimensions cat-resp))
                           :tags (count (:tags tag-resp))
                           :grounding-source (:enrichment-grounding-source merged)})
                (store/set-enriched! ds id merged))))
        (catch Exception e
          (log/warn e "Tunabrain enrichment call failed; leaving enriched=false"
                    {:id id})
          nil)))))
