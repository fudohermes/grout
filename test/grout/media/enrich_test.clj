(ns grout.media.enrich-test
  "Tests for the enrichment orchestrator. The orchestrator:
    1. Reads the row
    2. Calls Tunabrain /categorize (with replayed context)
    3. Calls Tunabrain /tags (with the categorize-returned context)
    4. Merges results and persists

  The `set-enriched!` store fn is mocked; the real DB is not touched.
  The `request-categorization!` and `request-tags!` tunabrain fns are
  mocked; the real HTTP client is not touched."
  (:require [clojure.test :refer [deftest is]]
            [grout.media.enrich :as enrich]
            [grout.media.store :as store]
            [grout.tunabrain :as tb]))

;; --- merge-enrichment -------------------------------------------------------

(deftest merge-enrichment-unions-tags-with-dimension-prefix
  (let [row    {:id (java.util.UUID/randomUUID) :name "Keep" :tags ["existing" "filename:foo.mp4"]}
        cat    {:dimensions [{:dimension "audience" :values ["kids" "family"]}
                             {:dimension "channel"  :values ["goldenreels"]}]
                :mappings []
                :context  {:summary "s" :source "provided-text" :links []}}
        tag    {:tags ["new-ai-tag" "kids"]
                :context {:summary "s2" :source "wikipedia" :links ["..."]}}
        m      (enrich/merge-enrichment row cat tag)]
    (is (= ["existing" "filename:foo.mp4"
            "audience:kids" "audience:family" "channel:goldenreels"
            "new-ai-tag" "kids"]
           (:tags m))
        "tags union: existing + dimension-as-tag prefix + ai tags, all preserved in order, deduped")
    (is (= "wikipedia" (:enrichment-grounding-source m))
        "uses the LAST context (tags call ran second) for grounding source")
    (is (= {:summary "s2" :source "wikipedia" :links ["..."]} (:enrichment-context m))
        "uses the last context verbatim for replay")))

(deftest merge-enrichment-dedups-case-sensitive
  (let [row  {:tags ["tag" "Tag" "TAG"]}
        cat  {:dimensions [] :context nil}
        tag  {:tags ["tag" "new"] :context nil}
        m    (enrich/merge-enrichment row cat tag)]
    (is (= ["tag" "Tag" "TAG" "new"] (:tags m)) "case-sensitive dedup (matches Tunabrain's behavior)")))

(deftest merge-enrichment-drops-blank-tags
  (let [m (enrich/merge-enrichment
            {:tags ["  " "" "good"]}
            {:dimensions [] :context nil}
            {:tags ["" "  " "ai"] :context nil})]
    (is (= ["good" "ai"] (:tags m)))))

(deftest merge-enrichment-falls-back-to-categorize-context
  (let [m (enrich/merge-enrichment
            {:tags []}
            {:dimensions [] :context {:summary "s1" :source "wikipedia"}}
            {:tags ["ai"] :context nil})]
    (is (= "wikipedia" (:enrichment-grounding-source m))
        "uses categorize's context when tags call returned no context")
    (is (= {:summary "s1" :source "wikipedia"} (:enrichment-context m)))))

(deftest merge-enrichment-defaults-grounding-source-to-none
  (let [m (enrich/merge-enrichment
            {:tags []}
            {:dimensions [] :context nil}
            {:tags ["ai"] :context nil})]
    (is (= "none" (:enrichment-grounding-source m)))))

;; --- enrich-one! ------------------------------------------------------------

(deftest enrich-one-calls-both-endpoints-and-persists
  (let [row     (atom {:id (java.util.UUID/randomUUID)
                       :name "Test"
                       :tags ["existing" "filename:foo.mp4"]
                       :enrichment_context nil})
        saved   (atom nil)
        cat-calls (atom 0)
        tag-calls (atom 0)]
    (with-redefs [store/find-by-id   (fn [_ _ & _] @row)
                  store/set-enriched! (fn [_ _ data] (reset! saved data)
                                        (swap! row assoc :enriched true) @row)
                  tb/request-categorization! (fn [_ _ _ & _]
                                                (swap! cat-calls inc)
                                                {:dimensions [{:dimension "audience"
                                                               :values ["kids"]}]
                                                 :mappings []
                                                 :context {:summary "s" :source "provided-text"}})
                  tb/request-tags! (fn [_ _ existing & _]
                                      (swap! tag-calls inc)
                                      (is (= ["existing" "filename:foo.mp4"] existing))
                                      {:tags ["ai-tag"] :context {:summary "s" :source "provided-text"}})]
      (let [result (enrich/enrich-one! nil nil
                                      {:audience {:description "A" :values ["kids"]}}
                                      (:id @row))]
        (is (some? result))
        (is (= 1 @cat-calls))
        (is (= 1 @tag-calls))
        (is (= ["existing" "filename:foo.mp4" "audience:kids" "ai-tag"] (:tags @saved)))
        (is (= "provided-text" (:enrichment-grounding-source @saved)))))))

(deftest enrich-one-replays-stored-context
  (let [stored-ctx {:summary "corrected by human" :source "provided-summary" :links []}
        captured   (atom nil)
        row        {:id (java.util.UUID/randomUUID)
                    :name "x"
                    :tags []
                    :enrichment_context stored-ctx}]
    (with-redefs [store/find-by-id   (fn [_ _ & _] row)
                  store/set-enriched! (fn [_ _ _] row)
                  tb/request-categorization! (fn [_ _ _ & {:keys [context]}]
                                                (reset! captured context)
                                                {:dimensions [] :mappings []
                                                 :context {:source "provided-summary"}})
                  tb/request-tags! (fn [_ _ _ & _] {:tags [] :context {:source "provided-summary"}})]
      (enrich/enrich-one! nil nil {:audience {:description "A" :values ["kids"]}}
                          (:id row))
      (is (= stored-ctx @captured) "stored context is replayed to /categorize"))))

(deftest enrich-one-forwards-tags-context-to-tags-call
  (let [cat-context {:summary "from categorize" :source "wikipedia" :links []}
        captured    (atom nil)
        row         {:id (java.util.UUID/randomUUID) :name "x" :tags []}]
    (with-redefs [store/find-by-id   (fn [_ _ & _] row)
                  store/set-enriched! (fn [_ _ _] row)
                  tb/request-categorization! (fn [_ _ _ & _]
                                                {:dimensions []
                                                 :mappings []
                                                 :context cat-context})
                  tb/request-tags! (fn [_ _ _ & {:keys [context]}]
                                      (reset! captured context)
                                      {:tags ["ai"] :context {:source "wikipedia"}})]
      (enrich/enrich-one! nil nil {:audience {:description "A" :values ["kids"]}}
                          (:id row))
      (is (= cat-context @captured)
          "tags call receives the categorize-returned context"))))

(deftest enrich-one-returns-nil-when-both-empty
  (let [row {:id (java.util.UUID/randomUUID) :name "x" :tags []}]
    (with-redefs [store/find-by-id   (fn [_ _ & _] row)
                  tb/request-categorization! (fn [& _] {:dimensions [] :mappings [] :context nil})
                  tb/request-tags! (fn [& _] {:tags [] :context nil})]
      (is (nil? (enrich/enrich-one! nil nil
                                    {:audience {:description "A" :values ["kids"]}}
                                    (:id row)))
          "row stays enriched=false so a later sweep can retry"))))

(deftest enrich-one-returns-nil-when-tunabrain-throws
  (let [row {:id (java.util.UUID/randomUUID) :name "x" :tags []}]
    (with-redefs [store/find-by-id (fn [_ _ & _] row)
                  tb/request-categorization! (fn [& _]
                                                (throw (ex-info "boom" {})))]
      (is (nil? (enrich/enrich-one! nil nil
                                    {:audience {:description "A" :values ["kids"]}}
                                    (:id row)))
          "tunabrain errors are caught; row stays enriched=false"))))

(deftest enrich-one-returns-nil-when-row-missing
  (with-redefs [store/find-by-id (fn [_ _ & _] nil)]
    (is (nil? (enrich/enrich-one! nil nil
                                  {:audience {:description "A" :values ["kids"]}}
                                  (java.util.UUID/randomUUID))))))
