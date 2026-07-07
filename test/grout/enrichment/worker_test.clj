(ns grout.enrichment.worker-test
  (:require [clojure.test :refer [deftest is]]
            [grout.enrichment.worker :as worker]
            [grout.media.enrich :as enrich]
            [grout.media.store :as store]))

;; The `tunabrain` arg to the worker is now an orchestrator map carrying
;; both the TunabrainClient and the `dim-config` (the dimensions
;; catalog fetched at startup from Tunarr Scheduler). See
;; `grout.tunarr_scheduler` and the design doc for the rationale.

(def ^:private fake-orchestrator
  {:dim-config {:audience {:description "A" :values ["kids"]}}})

(deftest run-once-processes-pending
  (let [processed (atom [])]
    (with-redefs [store/unenriched  (fn [_ _] [{:id 1} {:id 2}])
                  enrich/enrich-one! (fn [_ _ _ id] (swap! processed conj id))]
      (is (= 2 (worker/run-once! nil fake-orchestrator 10)))
      (is (= [1 2] @processed)))))

(deftest run-once-passes-dim-config-to-enrich
  (let [captured (atom nil)]
    (with-redefs [store/unenriched  (fn [_ _] [{:id 1}])
                  enrich/enrich-one! (fn [_ _ dim-config id]
                                       (reset! captured [dim-config id])
                                       nil)]
      (worker/run-once! nil fake-orchestrator 10))
    (is (= [{:audience {:description "A" :values ["kids"]}} 1] @captured)
        "the worker's orchestrator map's :dim-config is forwarded to enrich-one!")))

(deftest run-once-continues-past-errors
  (let [processed (atom [])]
    (with-redefs [store/unenriched  (fn [_ _] [{:id 1} {:id 2}])
                  enrich/enrich-one! (fn [_ _ _ id]
                                       (if (= id 1)
                                         (throw (RuntimeException. "x"))
                                         (swap! processed conj id)))]
      (is (= 2 (worker/run-once! nil fake-orchestrator 10)))
      (is (= [2] @processed) "one failure does not abort the sweep"))))

(deftest disabled-worker-is-noop
  (is (nil? (:executor (worker/start! {:enabled false})))))
