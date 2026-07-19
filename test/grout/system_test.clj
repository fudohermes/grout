(ns grout.system-test
  "Tests for the Integrant system wiring in `grout.system`. These exist
  primarily to catch wiring bugs that the rest of the test suite
  cannot see — for example, a config key whose value is `nil` but
  whose `init-key` does not guard against it."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [grout.system :as system]))

;; ---------------------------------------------------------------------------
;; Original config-wiring tests (these were already in the test suite;
;; they exercise the basic shape of `->system-config` so that an
;; operator changing the config map can quickly see what blows up).
;; ---------------------------------------------------------------------------

(deftest system-config-has-expected-components
  (let [c (system/->system-config {:database {:jdbc-url "jdbc:x"}})]
    (is (every? c [:grout/logger :grout/db :grout/tunabrain :grout/media
                   :grout/enrichment-worker :grout/retention-job :grout/http]))))

(deftest enabled-flags-coerced-from-strings
  (let [c (system/->system-config {:enrichment {:enabled "false"}
                                   :retention  {:enabled "true"}})]
    (is (false? (get-in c [:grout/enrichment-worker :enabled])))
    (is (true?  (get-in c [:grout/retention-job :enabled])))))

(deftest media-profile-threaded-through
  (let [profile {:vcodec "h264" :acodec "aac"}
        c (system/->system-config {:media {:media-dir "/m" :profile profile}})]
    (is (= profile (get-in c [:grout/media :profile])))
    (is (= "/m"   (get-in c [:grout/media :media-dir])))))

(deftest media-staging-dir-threaded-through
  (testing "explicit :staging-dir is passed through"
    (let [c (system/->system-config {:media {:media-dir "/m" :staging-dir "/m/.staging"}})]
      (is (= "/m/.staging" (get-in c [:grout/media :staging-dir])))))
  (testing "falls back to a subdirectory when unset"
    (let [c (system/->system-config {:media {:media-dir "/m"}})]
      (is (= "/data/media/grout/.staging" (get-in c [:grout/media :staging-dir]))))))

;; ---------------------------------------------------------------------------
;; Regression: the runtime crash on the live cluster (2026-07-07) was
;; caused by the :grout/tunarr-scheduler init-key being invoked with
;; cfg=nil when TUNARR_SCHEDULER_URL was unset. The Integrant
;; multimethod dispatches on the config-map key regardless of whether
;; the value is nil, so the init-key has to guard explicitly.
;; ---------------------------------------------------------------------------

(deftest tunarr-scheduler-init-handles-nil-config
  (testing "init-key returns nil when cfg is nil (no throw)"
    ;; The optional-dependency case: the upstream config resolved to
    ;; nil (TUNARR_SCHEDULER_URL unset, or an empty string). The
    ;; init-key must silently return nil so :grout/dim-catalog can
    ;; fall back to an empty dim-config.
    (is (nil? (ig/init-key :grout/tunarr-scheduler nil)))))

(deftest tunarr-scheduler-init-handles-missing-endpoint
  (testing "init-key throws helpfully when cfg has no :endpoint"
    ;; The endpoint-present-but-empty case is a config bug, not the
    ;; optional-dependency case. Pre-fix this also threw, but with a
    ;; confusing message that the operator might miss in logs.
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"requires :endpoint"
          (ig/init-key :grout/tunarr-scheduler {})))))
