(ns grout.http.routes-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [ring.mock.request :as mock]
            [grout.http.routes :as routes]
            [grout.media.store :as store]))

(def ^:private fake-db
  (reify Object
    (toString [_] "fake-datasource")))

(defn- handler []
  (routes/handler {:db fake-db :media {:ds fake-db :media-dir "/tmp"}}))

(def ^:private sample-id (java.util.UUID/randomUUID))

(def ^:private sample-row
  {:id sample-id
   :kind "bumper"
   :path "/data/media/grout/x.mp4"
   :name "Test Bumper"
   :description nil
   :channel "britannia"
   :tags ["daytime" "fun"]
   :duration_ms 65000
   :width 1920
   :height 1080
   :vcodec "h264"
   :acodec "aac"
   :source "tunarr-bumper"
   :source_url nil
   :enriched false
   :created_at (java.time.Instant/now)
   :superseded_at nil})

(defn- json-req [method uri body]
  (-> (mock/request method uri)
      (mock/content-type "application/json")
      (mock/body (json/generate-string body))))

;; --- health / meta ---------------------------------------------------------

(deftest health-ok-when-db-ping-succeeds
  (with-redefs [store/query (constantly [])
                grout.db/check-connection (fn [_] {:ok true})]
    (let [response ((handler) (mock/request :get "/health"))]
      (is (= 200 (:status response)))
      (is (= "ok" (get-in response [:body :status])))
      (is (= "ok" (get-in response [:body :database]))))))

(deftest health-degraded-when-db-ping-fails
  (with-redefs [grout.db/check-connection (fn [_] {:ok false :error "timeout"})]
    (let [response ((handler) (mock/request :get "/health"))]
      (is (= 503 (:status response)))
      (is (= "degraded" (get-in response [:body :status])))
      (is (= "error" (get-in response [:body :database]))))))

(deftest version-returns-metadata
  (let [response ((handler) (mock/request :get "/api/version"))]
    (is (= 200 (:status response)))
    (is (contains? (:body response) :git-commit))))

(deftest not-found-handler
  (let [response ((handler) (mock/request :get "/no-such-route"))]
    (is (= 404 (:status response)))))

;; --- media query -----------------------------------------------------------

(deftest query-returns-summaries
  (with-redefs [store/query (fn [_ _] [sample-row])]
    (let [resp ((handler) (mock/request
                           :get "/grout/media?tags=fun,daytime&min_ms=1000&random=true&limit=5"))]
      (is (= 200 (:status resp)))
      (is (= 1 (get-in resp [:body :count])))
      (let [item (first (get-in resp [:body :items]))]
        (is (= (str sample-id) (:id item)))
        (is (= 65000 (:duration-ms item)))
        (is (= (str "/grout/media/" sample-id "/stream") (:stream-url item)))
        (is (= ["daytime" "fun"] (:tags item)))))))

(deftest query-passes-parsed-params-to-store
  (let [captured (atom nil)]
    (with-redefs [store/query (fn [_ params] (reset! captured params) [])]
      ((handler) (mock/request
                  :get "/grout/media?channel=britannia&tags=a,b&min_ms=100&max_ms=200&kind=bumper&random=true"))
      (is (= "britannia" (:channel @captured)))
      (is (= ["a" "b"] (:tags @captured)))
      (is (= 100 (:min-ms @captured)))
      (is (= 200 (:max-ms @captured)))
      (is (= "bumper" (:kind @captured)))
      (is (true? (:random @captured))))))

;; --- fetch one -------------------------------------------------------------

(deftest get-one-found
  (with-redefs [store/find-by-id (fn [_ _ & _] sample-row)]
    (let [resp ((handler) (mock/request :get (str "/grout/media/" sample-id)))]
      (is (= 200 (:status resp)))
      (is (= "bumper" (get-in resp [:body :kind])))
      (is (= false (get-in resp [:body :enriched]))))))

(deftest get-one-not-found
  (with-redefs [store/find-by-id (fn [_ _ & _] nil)]
    (let [resp ((handler) (mock/request :get (str "/grout/media/" sample-id)))]
      (is (= 404 (:status resp))))))

;; --- patch -----------------------------------------------------------------

(deftest patch-updates-metadata
  (with-redefs [store/update-metadata! (fn [_ _ _] (assoc sample-row :name "Renamed"))]
    (let [resp ((handler) (json-req :patch (str "/grout/media/" sample-id) {:name "Renamed"}))]
      (is (= 200 (:status resp)))
      (is (= "Renamed" (get-in resp [:body :name]))))))

(deftest patch-empty-body-is-400
  (let [resp ((handler) (json-req :patch (str "/grout/media/" sample-id) {}))]
    (is (= 400 (:status resp)))))

(deftest patch-not-found
  (with-redefs [store/update-metadata! (fn [_ _ _] nil)]
    (let [resp ((handler) (json-req :patch (str "/grout/media/" sample-id) {:name "X"}))]
      (is (= 404 (:status resp))))))

;; --- delete ----------------------------------------------------------------

(deftest delete-soft
  (with-redefs [store/soft-delete! (fn [_ _] sample-row)]
    (let [resp ((handler) (mock/request :delete (str "/grout/media/" sample-id)))]
      (is (= 200 (:status resp)))
      (is (false? (get-in resp [:body :hard]))))))

(deftest delete-soft-not-found
  (with-redefs [store/soft-delete! (fn [_ _] nil)]
    (let [resp ((handler) (mock/request :delete (str "/grout/media/" sample-id)))]
      (is (= 404 (:status resp))))))

(deftest delete-hard-unlinks
  (with-redefs [store/hard-delete! (fn [_ _] sample-row)]
    (let [resp ((handler) (mock/request :delete (str "/grout/media/" sample-id "?hard=true")))]
      (is (= 200 (:status resp)))
      (is (true? (get-in resp [:body :hard]))))))

;; --- tags ------------------------------------------------------------------

(deftest get-tags-returns-list
  (with-redefs [store/find-by-id (fn [_ _ & _] sample-row)]
    (let [resp ((handler) (mock/request :get (str "/grout/media/" sample-id "/tags")))]
      (is (= 200 (:status resp)))
      (is (= ["daytime" "fun"] (get-in resp [:body :tags]))))))

(deftest add-tag-returns-201
  (with-redefs [store/add-tag! (fn [_ _ tag] (update sample-row :tags conj tag))]
    (let [resp ((handler) (json-req :post (str "/grout/media/" sample-id "/tags") {:tag "kids"}))]
      (is (= 201 (:status resp)))
      (is (some #{"kids"} (get-in resp [:body :tags]))))))
