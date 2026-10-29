(ns grout.media.hash-test
  (:require [clojure.test :refer [deftest is]]
            [grout.media.hash :as hash]))

(deftest sha256-matches-known-vector
  (let [f (java.io.File/createTempFile "grout-hash" ".bin")]
    (try
      (spit f "abc")
      ;; sha256("abc") — the standard NIST test vector
      (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
             (hash/sha256-file (.getPath f))))
      (finally (.delete f)))))

(deftest content-path-is-sharded
  (is (= "/data/media/grout/ba/ba7816bf.mp4"
         (hash/content-path "/data/media/grout" "ba7816bf"))))

(deftest copy-and-hash-matches-sha256-file
  (let [f (java.io.File/createTempFile "grout-hash" ".bin")]
    (try
      (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
             (hash/copy-and-hash! (java.io.ByteArrayInputStream. (.getBytes "abc")) f)))
      (is (= "abc" (slurp f)))
      (finally (.delete f)))))
