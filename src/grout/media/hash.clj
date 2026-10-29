(ns grout.media.hash
  "Content hashing for dedup / idempotent intake. Hashes the ORIGINAL source
   bytes (not the normalized output) so a client can hash a local file and ask
   Grout whether an upload is needed."
  (:require [clojure.java.io :as io])
  (:import [java.security DigestInputStream MessageDigest]))

(defn- hex [^bytes digest]
  (apply str (map #(format "%02x" (bit-and % 0xff)) digest)))

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
    (hex (.digest md))))

(defn copy-and-hash!
  "Copy `in` to file `out` and return the SHA-256 (as `sha256-file` would) of
   the bytes written, computed in the same pass. Upload spooling uses this so
   intake doesn't read the whole file back just to hash it."
  [^java.io.InputStream in out]
  ;; `in` is the caller's (e.g. the multipart part's) stream: read it through
  ;; the digest but leave closing it to the caller, as io/copy would.
  (let [md (MessageDigest/getInstance "SHA-256")]
    (io/copy (DigestInputStream. in md) (io/file out))
    (hex (.digest md))))

(defn content-path
  "Content-addressed storage path for `hash` under `base-dir`:
   <base-dir>/<ab>/<hash>.mp4 (sharded by the first two hex chars)."
  [base-dir hash]
  (str base-dir "/" (subs hash 0 2) "/" hash ".mp4"))
