(ns grout.media.intake
  "Intake pipeline (GROUT.md §8): probe -> normalize -> insert (enriched=false)
   -> kick async enrichment. Operates on files already present on the mount."
  (:require [grout.media.probe :as probe]
            [grout.media.store :as store]
            [taoensso.timbre :as log]))

(defn intake!
  "Run the intake pipeline for `req` (a map with :path :kind and optional
   :channel :tags :source :source-url :name :description) and return the created
   media row.

   `media` is the store component: {:ds ... :media-dir ... :profile? ...
   :enrich-fn?}. When present, `:enrich-fn` is called with the new row's id to
   kick asynchronous enrichment; intake never blocks on the AI call."
  [{:keys [ds profile enrich-fn]}
   {:keys [path kind channel tags source source-url name description]}]
  (let [{final-path :path pr :probe normalized :normalized}
        (probe/normalize! path (or profile probe/default-profile))]
    (when-not (:duration-ms pr)
      (throw (ex-info "Could not determine media duration" {:path final-path})))
    (log/info "Intake probed" {:path final-path :normalized normalized
                               :duration-ms (:duration-ms pr)})
    (let [row (store/create! ds
                             {:kind kind
                              :path final-path
                              :name name
                              :description description
                              :channel channel
                              :tags (vec (or tags []))
                              :duration_ms (:duration-ms pr)
                              :width (:width pr)
                              :height (:height pr)
                              :vcodec (:vcodec pr)
                              :acodec (:acodec pr)
                              :source source
                              :source_url source-url
                              :enriched false})]
      (when enrich-fn
        (enrich-fn (:id row)))
      row)))
