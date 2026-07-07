(ns grout.http.stream
  "Byte-range HTTP streaming fallback (GROUT.md §7). PV's primary path is
   by-path off the shared mount; this endpoint serves remote/non-co-mounted
   callers and honours HTTP Range requests."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [grout.media.store :as store])
  (:import [java.io ByteArrayInputStream File FileInputStream InputStream]
           [java.nio.channels FileChannel]))

(def ^:private content-types
  {"mp4" "video/mp4" "m4v" "video/mp4" "webm" "video/webm"
   "mkv" "video/x-matroska" "mov" "video/quicktime"
   "avi" "video/x-msvideo" "ts" "video/mp2t"})

(defn- content-type [path]
  (let [ext (some-> (re-find #"\.([^.]+)$" path) second str/lower-case)]
    (get content-types ext "application/octet-stream")))

(defn parse-range
  "Parse a single 'bytes=' Range header into an inclusive [start end], clamped
   to `len`. Returns nil when there is no usable byte range, or :unsatisfiable
   when the requested start is past the end of the file."
  [header ^long len]
  (when (and header (str/starts-with? header "bytes="))
    (let [[s e] (str/split (subs header 6) #"-" 2)
          s (when (seq s) (parse-long s))
          e (when (seq e) (parse-long e))]
      (cond
        (and (nil? s) (nil? e)) nil
        (nil? s)                [(max 0 (- len e)) (dec len)]        ; suffix
        (>= s len)              :unsatisfiable
        (nil? e)                [s (dec len)]                        ; open-ended
        :else                   [s (min e (dec len))]))))

(defn- range-bytes
  "Read `length` bytes from `f` starting at byte `start` (0-based, inclusive)
  and return them as a `ByteArrayInputStream`.

  Loading the slice into memory is intentional: Jetty 12.1's `HttpOutput`
  requires the response body's `InputStream` to behave consistently with its
  HTTP/1.1 write-back-pressure accounting. The previous `proxy [InputStream]`
  implementation (`bounded-stream`, removed) made Jetty's write loop see
  `written 0 < content-length` and fail with HTTP 500, because the proxy's
  custom `read` methods didn't satisfy Jetty's expectation that the
  underlying stream drain in well-defined chunks. `ByteArrayInputStream`
  is JDK-blessed and has the exact semantics Jetty expects.

  For a typical video Range request the slice is 1-10 MB (HTTP video
  seekers use small ranges; long sequential reads should not be served
  via HTTP at all — use the by-path fast path on the shared mount, which
  is the documented primary path per GROUT.md §7). The memory cost is
  bounded by the request's own `Content-Length`."
  ^InputStream [^File f ^long start ^long length]
  (let [buf (byte-array length)
        ^FileInputStream in (FileInputStream. f)]
    (try
      (let [^FileChannel ch (.getChannel in)]
        (.position ch (long start))
        (let [offset (atom 0)]
          (while (< @offset length)
            (let [n (.read in buf (int @offset) (int (- length @offset)))]
              (when (neg? n)
                (throw (java.io.EOFException.
                         (str "unexpected EOF at " (+ @offset n)
                              " of " length " bytes from " (.getPath f)))))
              (swap! offset + n)))))
      (finally (.close in)))
    (ByteArrayInputStream. buf)))

(defn stream-handler [{:keys [ds]}]
  (fn [{{{:keys [id]} :path} :parameters :as req}]
    (if-let [row (store/find-by-id ds id {:include-superseded? true})]
      (let [^File f (io/file (:path row))]
        (if-not (.exists f)
          {:status 404 :body {:error "Media file missing on disk"}}
          (let [len (.length f)
                ct  (content-type (:path row))
                rng (parse-range (get-in req [:headers "range"]) len)]
            (cond
              (= rng :unsatisfiable)
              {:status 416
               :headers {"Content-Range" (str "bytes */" len)
                         "Accept-Ranges" "bytes"}
               :body {:error "Requested range not satisfiable"}}

              (nil? rng)
              {:status 200
               :headers {"Content-Type" ct
                         "Content-Length" (str len)
                         "Accept-Ranges" "bytes"}
               :body f}

              :else
              (let [[start end] rng
                    length (inc (- end start))]
                {:status 206
                 :headers {"Content-Type" ct
                           "Content-Length" (str length)
                           "Accept-Ranges" "bytes"
                           "Content-Range" (str "bytes " start "-" end "/" len)}
                 :body (range-bytes f (long start) (long length))})))))
      {:status 404 :body {:error "Not found"}})))
