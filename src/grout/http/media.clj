(ns grout.http.media
  "HTTP handlers for the media API. Each constructor closes over the media store
   component ({:ds ... :media-dir ...}) and returns a ring handler."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [grout.media.enrich :as enrich]
            [grout.media.intake :as intake]
            [grout.media.store :as store]
            [taoensso.timbre :as log]))

(defn- stream-url [id]
  (str "/grout/media/" id "/stream"))

(defn- row->summary [row]
  {:id (:id row)
   :name (:name row)
   :duration-ms (:duration_ms row)
   :path (:path row)
   :stream-url (stream-url (:id row))
   :vcodec (:vcodec row)
   :acodec (:acodec row)
   :tags (vec (:tags row))})

(defn- row->full [row]
  {:id (:id row)
   :kind (:kind row)
   :path (:path row)
   :name (:name row)
   :description (:description row)
   :channel (:channel row)
   :tags (vec (:tags row))
   :duration-ms (:duration_ms row)
   :width (:width row)
   :height (:height row)
   :vcodec (:vcodec row)
   :acodec (:acodec row)
   :source (:source row)
   :source-url (:source_url row)
   :enriched (:enriched row)
   :content-hash (:content_hash row)
   :stream-url (stream-url (:id row))
   :created-at (some-> (:created_at row) str)
   :superseded-at (some-> (:superseded_at row) str)})

(defn- parse-tags
  "Split a comma-separated tag string into a vector, trimming and dropping
   blanks. Returns nil for a nil/blank input."
  [s]
  (when (some? s)
    (let [ts (->> (str/split s #",")
                  (map str/trim)
                  (remove str/blank?)
                  vec)]
      (when (seq ts) ts))))

(def ^:private not-found
  {:status 404 :body {:error "Not found"}})

(defn- under-dir?
  "True when `path` resolves within `dir` (or `dir` is nil). Guards intake
   against reading arbitrary files off the mount."
  [dir path]
  (or (nil? dir)
      (let [d (str (.getCanonicalPath (io/file dir)) java.io.File/separator)
            p (.getCanonicalPath (io/file path))]
        (or (= p (.getCanonicalPath (io/file dir)))
            (.startsWith p d)))))

(defn intake-handler [{:keys [media-dir] :as media}]
  (fn [{{body :body} :parameters}]
    (let [path (:path body)]
      (cond
        (not (.exists (io/file path)))
        {:status 400 :body {:error (str "File not found: " path)}}

        (not (under-dir? media-dir path))
        {:status 400 :body {:error "Path is outside the media directory"}}

        :else
        (try
          (let [{:keys [row deduplicated]} (intake/intake! media body)]
            {:status (if deduplicated 200 201)
             :body (row->full row)})
          (catch clojure.lang.ExceptionInfo e
            (log/error e "Intake failed" (ex-data e))
            {:status 422 :body {:error (ex-message e)}}))))))

(defn get-by-hash-handler [{:keys [ds]}]
  (fn [{{{:keys [hash]} :path} :parameters}]
    (if-let [row (store/find-by-hash ds hash)]
      {:status 200 :body (row->full row)}
      not-found)))

(defn query-handler [{:keys [ds]}]
  (fn [{{q :query} :parameters}]
    (let [params {:channel (:channel q)
                  :tags    (parse-tags (:tags q))
                  :min-ms  (:min_ms q)
                  :max-ms  (:max_ms q)
                  :kind    (:kind q)
                  :limit   (or (:limit q) 10)
                  :random  (boolean (:random q))}
          rows (store/query ds params)]
      {:status 200
       :body {:count (count rows)
              :items (mapv row->summary rows)}})))

(defn get-one-handler [{:keys [ds]}]
  (fn [{{{:keys [id]} :path} :parameters}]
    (if-let [row (store/find-by-id ds id)]
      {:status 200 :body (row->full row)}
      not-found)))

(defn patch-handler [{:keys [ds]}]
  (fn [{{{:keys [id]} :path body :body} :parameters}]
    (let [patch (select-keys body [:name :description :channel :tags])]
      (if (empty? patch)
        {:status 400 :body {:error "No mutable fields provided"}}
        (if-let [row (store/update-metadata! ds id patch)]
          {:status 200 :body (row->full row)}
          not-found)))))

(defn delete-handler [{:keys [ds]}]
  (fn [{{{:keys [id]} :path {:keys [hard]} :query} :parameters}]
    (if hard
      (if-let [row (store/hard-delete! ds id)]
        (do (when-let [p (:path row)]
              (try (io/delete-file p true)
                   (catch Exception e
                     (log/warn e "Failed to unlink media file" {:path p}))))
            {:status 200 :body {:deleted true :hard true}})
        not-found)
      (if (store/soft-delete! ds id)
        {:status 200 :body {:deleted true :hard false}}
        not-found))))

(defn get-tags-handler [{:keys [ds]}]
  (fn [{{{:keys [id]} :path} :parameters}]
    (if-let [row (store/find-by-id ds id {:include-superseded? true})]
      {:status 200 :body {:tags (vec (:tags row))}}
      not-found)))

(defn add-tag-handler [{:keys [ds]}]
  (fn [{{{:keys [id]} :path {:keys [tag]} :body} :parameters}]
    (if-let [row (store/add-tag! ds id tag)]
      {:status 201 :body {:tags (vec (:tags row))}}
      not-found)))

(defn enrich-handler [{:keys [ds tunabrain]}]
  (fn [{{{:keys [id]} :path} :parameters}]
    (if-let [row (enrich/enrich-one! ds tunabrain id)]
      {:status 200 :body (row->full row)}
      (if (store/find-by-id ds id {:include-superseded? true})
        {:status 502 :body {:error "Enrichment failed or produced no metadata"}}
        not-found))))
