(ns grout.http.upload
  "A multipart storage engine for POST /grout/media uploads.

   Mirrors ring.middleware.multipart-params.temp-file/temp-file-store, but
   spools uploaded bytes into a configured staging directory instead of the
   JVM's default temp directory (java.io.tmpdir). That default is frequently
   a small ramdisk in containerized deployments, which large media uploads
   can exhaust; the staging directory is expected to live on a real,
   deployment-sized volume instead."
  (:require [clojure.java.io :as io])
  (:import [java.io File]))

(defn staging-file-store
  "Returns a multipart :store function that writes each uploaded file into
   `dir` (created if it doesn't already exist). Callers are responsible for
   deleting the returned :tempfile once the upload has been processed — see
   grout.http.media/intake-handler's `finally` block."
  [dir]
  (let [dir-file (io/file dir)]
    (.mkdirs dir-file)
    (fn [{:keys [filename content-type stream]}]
      (let [temp-file (File/createTempFile "grout-upload-" nil dir-file)]
        (io/copy stream temp-file)
        {:filename filename
         :content-type content-type
         :tempfile temp-file
         :size (.length temp-file)}))))
