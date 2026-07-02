(ns grout.retention
  "Retention/lifecycle job (GROUT.md §10). Enforces a cap of N live items per
   (channel, kind, duration-bucket), superseding the oldest first — giving teeth
   to TS's max-bumpers-per-channel intent and keeping the AI-regenerated pool
   from growing unbounded."
  (:require [grout.media.store :as store]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent Executors TimeUnit]))

(defn bucket
  "Duration-bucket index for `duration-ms` given `bucket-ms` width (e.g. 5000
   mirrors TS's 5s bumper buckets). nil duration yields nil."
  [bucket-ms duration-ms]
  (when duration-ms (quot duration-ms bucket-ms)))

(defn plan-supersede
  "Pure retention planner. Given live `rows` (maps with :id :channel :kind
   :duration_ms :created_at) and a policy {:cap :bucket-ms}, return the ids to
   supersede: within each (channel, kind, duration-bucket) group, every row
   beyond the newest `cap`, oldest first."
  [rows {:keys [cap bucket-ms]}]
  (->> rows
       (group-by (juxt :channel :kind #(bucket bucket-ms (:duration_ms %))))
       (mapcat (fn [[_ group]]
                 (->> group
                      (sort-by :created_at #(compare %2 %1)) ; newest first
                      (drop cap)
                      (map :id))))
       vec))

(defn run-once!
  "Run one retention sweep. Returns the number of rows superseded."
  [ds policy]
  (let [ids (plan-supersede (store/live-rows-for-retention ds) policy)]
    (when (seq ids)
      (log/info "Retention superseding oldest over cap" {:count (count ids)})
      (store/supersede-many! ds ids))
    (count ids)))

(defn start!
  "Start the periodic retention job. Returns a component map for stop!.
   Set :enabled false to run a no-op."
  [{:keys [ds interval-ms cap bucket-ms enabled]
    :or   {interval-ms 3600000 cap 20 bucket-ms 5000 enabled true}}]
  (if-not enabled
    (do (log/info "Retention job disabled") {:executor nil})
    (let [exec   (Executors/newSingleThreadScheduledExecutor)
          policy {:cap cap :bucket-ms bucket-ms}]
      (.scheduleWithFixedDelay
       exec
       (fn [] (try (run-once! ds policy)
                   (catch Throwable t
                     (log/error t "Retention sweep crashed"))))
       (long interval-ms) (long interval-ms) TimeUnit/MILLISECONDS)
      (log/info "Retention job started"
                {:interval-ms interval-ms :cap cap :bucket-ms bucket-ms})
      {:executor exec})))

(defn stop! [{:keys [executor]}]
  (when executor
    (.shutdownNow executor)
    (log/info "Retention job stopped")))
