(ns grout.media.enrich
  "Enrichment orchestration (GROUT.md §9): read a row, ask Tunabrain for
   metadata, merge, and persist with enriched=true."
  (:require [grout.media.store :as store]
            [grout.tunabrain :as tb]
            [taoensso.timbre :as log]))

(defn merge-enrichment
  "Combine an existing row with an AI suggestion. Human-set name/description win
   when present; tags are unioned so suggestions never drop existing tags."
  [row suggestion]
  {:name        (or (not-empty (:name row)) (:name suggestion))
   :description (or (not-empty (:description row)) (:description suggestion))
   :tags        (vec (distinct (concat (vec (:tags row))
                                       (vec (:tags suggestion)))))})

(defn enrich-one!
  "Enrich a single media row via Tunabrain. Returns the updated row, or nil when
   the row is missing or Tunabrain produced no usable metadata (leaving
   enriched=false so a later sweep can retry)."
  [ds tunabrain id]
  (when-let [row (store/find-by-id ds id {:include-superseded? true})]
    (let [ctx        {:name (:name row)
                      :channel (:channel row)
                      :duration-ms (:duration_ms row)
                      :tags (:tags row)}
          suggestion (tb/suggest-metadata tunabrain ctx)]
      (if suggestion
        (do (log/info "Enriched media" {:id id})
            (store/set-enriched! ds id (merge-enrichment row suggestion)))
        (do (log/warn "No enrichment produced; leaving enriched=false" {:id id})
            nil)))))
