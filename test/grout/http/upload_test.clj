(ns grout.http.upload-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [grout.http.upload :as upload])
  (:import [java.io ByteArrayInputStream File]))

(deftest staging-file-store-writes-into-configured-dir
  (testing "creates the staging dir if missing, and spools uploads there"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                        (str "grout-upload-test-" (random-uuid)))
          store (upload/staging-file-store (.getAbsolutePath dir))
          bytes (.getBytes "hello world")
          item {:filename "clip.mp4"
                :content-type "video/mp4"
                :stream (ByteArrayInputStream. bytes)}
          result (store item)]
      (try
        (is (.isDirectory dir))
        (is (= "clip.mp4" (:filename result)))
        (is (= "video/mp4" (:content-type result)))
        (is (= (count bytes) (:size result)))
        (is (= "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
               (:sha256 result))
            "hash of the spooled bytes matches sha256sum")
        (let [^File tempfile (:tempfile result)]
          (is (.exists tempfile))
          (is (= (.getAbsolutePath dir) (.getAbsolutePath (.getParentFile tempfile))))
          (is (= "hello world" (slurp tempfile))))
        (finally
          (doseq [^File f (.listFiles dir)] (.delete f))
          (.delete dir))))))
