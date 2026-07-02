(ns grout.media.enrich-test
  (:require [clojure.test :refer [deftest is]]
            [grout.media.enrich :as enrich]
            [grout.media.store :as store]
            [grout.tunabrain :as tb]))

(deftest merge-prefers-human-metadata-and-unions-tags
  (let [row {:name "Keep Me" :description nil :tags ["existing"]}
        sug {:name "AI Name" :description "AI desc" :tags ["existing" "new"]}
        m   (enrich/merge-enrichment row sug)]
    (is (= "Keep Me" (:name m)) "existing name wins")
    (is (= "AI desc" (:description m)) "AI fills blank description")
    (is (= ["existing" "new"] (:tags m)) "tags unioned, no dupes")))

(deftest enrich-one-persists-suggestion
  (let [saved (atom nil)]
    (with-redefs [store/find-by-id   (fn [_ _ & _] {:id 1 :name nil :tags []})
                  tb/suggest-metadata (fn [_ _] {:name "N" :description "D" :tags ["t"]})
                  store/set-enriched! (fn [_ _ data] (reset! saved data) {:id 1 :enriched true})]
      (is (some? (enrich/enrich-one! nil nil 1)))
      (is (= "N" (:name @saved)))
      (is (= ["t"] (:tags @saved))))))

(deftest enrich-one-nil-when-no-suggestion
  (with-redefs [store/find-by-id    (fn [_ _ & _] {:id 1 :tags []})
                tb/suggest-metadata (fn [_ _] nil)]
    (is (nil? (enrich/enrich-one! nil nil 1)))))

(deftest enrich-one-nil-when-row-missing
  (with-redefs [store/find-by-id (fn [_ _ & _] nil)]
    (is (nil? (enrich/enrich-one! nil nil 1)))))
