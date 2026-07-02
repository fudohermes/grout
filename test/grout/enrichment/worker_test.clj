(ns grout.enrichment.worker-test
  (:require [clojure.test :refer [deftest is]]
            [grout.enrichment.worker :as worker]
            [grout.media.enrich :as enrich]
            [grout.media.store :as store]))

(deftest run-once-processes-pending
  (let [processed (atom [])]
    (with-redefs [store/unenriched  (fn [_ _] [{:id 1} {:id 2}])
                  enrich/enrich-one! (fn [_ _ id] (swap! processed conj id))]
      (is (= 2 (worker/run-once! nil nil 10)))
      (is (= [1 2] @processed)))))

(deftest run-once-continues-past-errors
  (let [processed (atom [])]
    (with-redefs [store/unenriched  (fn [_ _] [{:id 1} {:id 2}])
                  enrich/enrich-one! (fn [_ _ id]
                                       (if (= id 1)
                                         (throw (RuntimeException. "x"))
                                         (swap! processed conj id)))]
      (is (= 2 (worker/run-once! nil nil 10)))
      (is (= [2] @processed) "one failure does not abort the sweep"))))

(deftest disabled-worker-is-noop
  (is (nil? (:executor (worker/start! {:enabled false})))))
