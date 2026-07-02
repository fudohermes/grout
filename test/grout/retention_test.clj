(ns grout.retention-test
  (:require [clojure.test :refer [deftest is]]
            [grout.media.store :as store]
            [grout.retention :as retention]))

(defn- row [id ch kind dur ts]
  {:id id :channel ch :kind kind :duration_ms dur :created_at ts})

(deftest bucket-computes-index
  (is (= 1 (retention/bucket 5000 5000)))
  (is (= 0 (retention/bucket 5000 4999)))
  (is (= 3 (retention/bucket 5000 15000)))
  (is (nil? (retention/bucket 5000 nil))))

(deftest keeps-newest-cap-per-bucket-supersedes-oldest
  (let [rows [(row 1 "b" "bumper" 5000 10)   ; bucket 1
              (row 2 "b" "bumper" 5200 20)   ; bucket 1
              (row 3 "b" "bumper" 5400 30)   ; bucket 1
              (row 4 "b" "bumper" 12000 15)] ; bucket 2
        ids  (set (retention/plan-supersede rows {:cap 2 :bucket-ms 5000}))]
    ;; bucket-1 group keeps newest two (3, 2), supersedes 1; bucket-2 kept
    (is (= #{1} ids))))

(deftest channel-and-generic-are-distinct-groups
  (let [rows [(row 1 nil "bumper" 5000 10)
              (row 2 nil "bumper" 5000 20)
              (row 3 "b" "bumper" 5000 30)]]
    (is (empty? (retention/plan-supersede rows {:cap 2 :bucket-ms 5000})))))

(deftest kind-separates-groups
  (let [rows [(row 1 "b" "bumper" 5000 10)
              (row 2 "b" "filler" 5000 20)
              (row 3 "b" "bumper" 5000 30)]]
    ;; cap 1: bumper group (1,3) supersedes 1; filler group (2) kept
    (is (= #{1} (set (retention/plan-supersede rows {:cap 1 :bucket-ms 5000}))))))

(deftest run-once-supersedes-plan
  (let [superseded (atom nil)]
    (with-redefs [store/live-rows-for-retention
                  (fn [_] [(row 1 "b" "bumper" 5000 10)
                           (row 2 "b" "bumper" 5000 20)
                           (row 3 "b" "bumper" 5000 30)])
                  store/supersede-many! (fn [_ ids] (reset! superseded (vec ids)))]
      (is (= 1 (retention/run-once! nil {:cap 2 :bucket-ms 5000})))
      (is (= [1] @superseded)))))

(deftest disabled-job-is-noop
  (is (nil? (:executor (retention/start! {:enabled false})))))
