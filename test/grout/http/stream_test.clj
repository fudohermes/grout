(ns grout.http.stream-test
  (:require [clojure.test :refer [deftest is]]
            [ring.mock.request :as mock]
            [grout.http.routes :as routes]
            [grout.http.stream :as stream]
            [grout.media.store :as store]))

(def ^:private fake-db (reify Object (toString [_] "db")))
(def ^:private id (java.util.UUID/randomUUID))

(defn- handler [] (routes/handler {:db fake-db :media {:ds fake-db}}))

(defn- temp-file [content]
  (let [f (java.io.File/createTempFile "grout-stream" ".mp4")]
    (spit f content)
    f))

;; --- parse-range (pure) ----------------------------------------------------

(deftest parse-range-variants
  (is (= [2 5] (stream/parse-range "bytes=2-5" 10)))
  (is (= [4 9] (stream/parse-range "bytes=4-" 10)) "open-ended")
  (is (= [5 9] (stream/parse-range "bytes=-5" 10)) "suffix")
  (is (= [0 9] (stream/parse-range "bytes=0-100" 10)) "clamped to end")
  (is (= :unsatisfiable (stream/parse-range "bytes=20-30" 10)))
  (is (nil? (stream/parse-range nil 10)))
  (is (nil? (stream/parse-range "none" 10))))

;; --- handler ---------------------------------------------------------------

(deftest range-request-returns-206-slice
  (let [f (temp-file "0123456789")]
    (try
      (with-redefs [store/find-by-id (fn [_ _ & _] {:id id :path (.getPath f)})]
        (let [resp ((handler) (-> (mock/request :get (str "/grout/media/" id "/stream"))
                                  (mock/header "Range" "bytes=2-5")))]
          (is (= 206 (:status resp)))
          (is (= "bytes 2-5/10" (get-in resp [:headers "Content-Range"])))
          (is (= "4" (get-in resp [:headers "Content-Length"])))
          (is (= "video/mp4" (get-in resp [:headers "Content-Type"])))
          ;; Regression for the "written 0 < content-length" Jetty 12.1 bug:
          ;; the body must actually carry the requested bytes (not 0 bytes,
          ;; not the whole file). On the pre-fix `proxy [InputStream]`
          ;; implementation, the proxy's `read` methods didn't satisfy
          ;; Jetty's HTTP/1.1 write-back-pressure accounting, so the response
          ;; body was effectively empty when served by Ring/jetty-adapter 1.15+.
          (let [body-bytes (slurp (:body resp))]
            (is (= 4 (count body-bytes)) "body must have exactly Content-Length bytes")
            (is (= "2345" body-bytes)))))
      (finally (.delete f)))))

(deftest range-request-larger-than-bounded-buffer
  ;; Regression for the proxy-based bounded-stream: a Range larger than
  ;; the default Jetty 12.1 output buffer (8 KB) used to fail with
  ;; `java.io.IOException: written 0 < content-length`. With the byte-slice
  ;; implementation, any Range size is fine because we hand Jetty a
  ;; `ByteArrayInputStream` over the full slice.
  (let [content (apply str (repeat 5000 "abcdefghij"))  ; 50,000 bytes
        f       (temp-file content)]
    (try
      (with-redefs [store/find-by-id (fn [_ _ & _] {:id id :path (.getPath f)})]
        (let [resp ((handler) (-> (mock/request :get (str "/grout/media/" id "/stream"))
                                  (mock/header "Range" "bytes=0-49999")))]
          (is (= 206 (:status resp)))
          (is (= "50000" (get-in resp [:headers "Content-Length"])))
          (is (= content (slurp (:body resp))))))
      (finally (.delete f)))))

(deftest full-request-returns-200
  (let [f (temp-file "0123456789")]
    (try
      (with-redefs [store/find-by-id (fn [_ _ & _] {:id id :path (.getPath f)})]
        (let [resp ((handler) (mock/request :get (str "/grout/media/" id "/stream")))]
          (is (= 200 (:status resp)))
          (is (= "10" (get-in resp [:headers "Content-Length"])))
          (is (= "bytes" (get-in resp [:headers "Accept-Ranges"])))))
      (finally (.delete f)))))

(deftest unsatisfiable-range-returns-416
  (let [f (temp-file "0123456789")]
    (try
      (with-redefs [store/find-by-id (fn [_ _ & _] {:id id :path (.getPath f)})]
        (let [resp ((handler) (-> (mock/request :get (str "/grout/media/" id "/stream"))
                                  (mock/header "Range" "bytes=50-60")))]
          (is (= 416 (:status resp)))
          (is (= "bytes */10" (get-in resp [:headers "Content-Range"])))))
      (finally (.delete f)))))

(deftest missing-row-returns-404
  (with-redefs [store/find-by-id (fn [_ _ & _] nil)]
    (let [resp ((handler) (mock/request :get (str "/grout/media/" id "/stream")))]
      (is (= 404 (:status resp))))))

(deftest missing-file-on-disk-returns-404
  (with-redefs [store/find-by-id (fn [_ _ & _] {:id id :path "/no/such/file.mp4"})]
    (let [resp ((handler) (mock/request :get (str "/grout/media/" id "/stream")))]
      (is (= 404 (:status resp))))))
