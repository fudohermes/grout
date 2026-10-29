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
    (with-redefs [store/mark-enrich-attempted! (fn [_ _] nil)
                  store/unenriched  (fn [_ _] [{:id 1} {:id 2}])
                  enrich/enrich-one! (fn [_ _ _ id] (swap! processed conj id))]
      (is (= 2 (worker/run-once! nil fake-orchestrator 10)))
      (is (= [1 2] @processed)))))

(deftest run-once-passes-dim-config-to-enrich
  (let [captured (atom nil)]
    (with-redefs [store/mark-enrich-attempted! (fn [_ _] nil)
                  store/unenriched  (fn [_ _] [{:id 1}])
                  enrich/enrich-one! (fn [_ _ dim-config id]
                                       (reset! captured [dim-config id])
                                       nil)]
      (worker/run-once! nil fake-orchestrator 10))
    (is (= [{:audience {:description "A" :values ["kids"]}} 1] @captured)
        "the worker's orchestrator map's :dim-config is forwarded to enrich-one!")))

;; Regression: ensure the worker unwraps :tunabrain from the orchestrator
;; map before passing it to enrich-one!. The pre-fix code passed the
;; whole map, which made the TunabrainClient read :endpoint on the
;; media map (where it doesn't exist) and call a nil host. The original
;; review was: "every scheduled enrichment sweep will build a request
;; against a nil host and fail — permanently, in production."
(deftest run-once-unwraps-tunabrain-client-from-orchestrator-map
  (let [fake-client    (reify Object (toString [_] "fake-tunabrain-client"))
        orchestrator   {:tunabrain fake-client
                        :dim-config {:audience {:description "A" :values ["kids"]}}}
        captured-client (atom nil)]
    (with-redefs [store/mark-enrich-attempted! (fn [_ _] nil)
                  store/unenriched  (fn [_ _] [{:id 1}])
                  enrich/enrich-one! (fn [_ client _dim-config _id]
                                       (reset! captured-client client)
                                       nil)]
      (worker/run-once! nil orchestrator 10))
    (is (identical? fake-client @captured-client)
        "the 2nd arg to enrich-one! must be the TunabrainClient record (from :tunabrain), not the orchestrator map")))

(deftest run-once-continues-past-errors
  (let [processed (atom [])]
    (with-redefs [store/mark-enrich-attempted! (fn [_ _] nil)
                  store/unenriched  (fn [_ _] [{:id 1} {:id 2}])
                  enrich/enrich-one! (fn [_ _ _ id]
                                       (if (= id 1)
                                         (throw (RuntimeException. "x"))
                                         (swap! processed conj id)))]
      (is (= 2 (worker/run-once! nil fake-orchestrator 10)))
      (is (= [2] @processed) "one failure does not abort the sweep"))))

(deftest disabled-worker-is-noop
  (is (nil? (:executor (worker/start! {:enabled false})))))

(deftest run-once-marks-each-row-attempted-before-enriching
  ;; Rows that Tunabrain can't enrich stay enriched=false; the attempt stamp
  ;; is what rotates them to the back of the queue so they can't starve it.
  (let [events (atom [])]
    (with-redefs [store/unenriched  (fn [_ _] [{:id 1} {:id 2}])
                  store/mark-enrich-attempted! (fn [_ id] (swap! events conj [:mark id]))
                  enrich/enrich-one! (fn [_ _ _ id] (swap! events conj [:enrich id]) nil)]
      (worker/run-once! nil fake-orchestrator 10))
    (is (= [[:mark 1] [:enrich 1] [:mark 2] [:enrich 2]] @events))))
