(ns grout.media.hash
  "Content hashing for dedup / idempotent intake. Hashes the ORIGINAL source
   bytes (not the normalized output) so a client can hash a local file and ask
   Grout whether an upload is needed."
  (:require [clojure.java.io :as io])
  (:import [java.security MessageDigest]))

(defn sha256-file
  "Return the lowercase hex SHA-256 of the file at `path`. Matches sha256sum."
  [path]
  (let [md  (MessageDigest/getInstance "SHA-256")
        buf (byte-array 8192)]
    (with-open [in (io/input-stream (io/file path))]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update md buf 0 n)
            (recur)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn content-path
  "Content-addressed storage path for `hash` under `base-dir`:
   <base-dir>/<ab>/<hash>.mp4 (sharded by the first two hex chars)."
  [base-dir hash]
  (str base-dir "/" (subs hash 0 2) "/" hash ".mp4"))
