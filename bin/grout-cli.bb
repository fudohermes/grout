#!/usr/bin/env bb
;; grout-cli — upload/tag filler media on a Grout server.
;;
;; Grout's intake endpoint (POST /grout/media) references a file already
;; reachable on the server's own filesystem (GROUT.md: "Body references a
;; file already on the mount"); it does not accept a multipart byte stream.
;; This CLI is therefore meant to run somewhere that shares that mount with
;; the Grout server (e.g. the arr-data co-mount) and simply tells the server
;; which path to intake. If the file isn't visible to the server at the same
;; path, intake will fail with "File not found".
;;
;; Before ever contacting the intake endpoint, the CLI hashes the file with
;; the same algorithm the server uses for its content-hash dedup key
;; (SHA-256 of the raw bytes, matching sha256sum) and looks it up via
;; GET /grout/by-hash/:hash. If the file is already stored, only the tags are
;; added (idempotent); otherwise the file is intaken with the requested tags.

(require '[babashka.cli :as cli]
         '[babashka.fs :as fs]
         '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(import '[java.security MessageDigest])

(def usage "
grout-cli — upload/tag filler media on a Grout server

Usage:
  grout-cli [options] <file> [<file> ...]

Options:
  -s, --server=URL       Grout server base URL (or set GROUT_URL)
      --tag=TAG          Add a tag (repeatable: --tag=a --tag=b)
      --tags=A,B,C       Add a comma-separated list of tags
      --kind=KIND        bumper | filler | program (default: filler)
      --channel=NAME     Owning channel; omit for generic/any-channel filler
      --source=NAME      Provenance label (default: upload)
      --source-url=URL   Origin URL for orphan/web content
      --name=STR         Title (only sensible for a single file)
      --description=STR  Description (only sensible for a single file)
      --no-filename-tag  Don't auto-add the default filename:<name> tag
      --dry-run          Hash and look up each file; don't upload or tag
      --json             Emit one JSON result object per line
  -v, --verbose          Print request details to stderr
  -h, --help             Show this help

By default every file also gets a `filename:<basename>` tag, so the original
filename is always searchable even after enrichment renames it.

Examples:
  grout-cli --tags=daytime,fun bumper1.mp4
  GROUT_URL=http://grout:8080 grout-cli --tag=kids --kind=filler *.mp4
")

(def cli-spec
  {:server        {:alias :s}
   :tag           {:collect []}
   :tags          {}
   :kind          {:default "filler"}
   :channel       {}
   :source        {:default "upload"}
   :source-url    {}
   :name          {}
   :description   {}
   :no-filename-tag {:coerce :boolean}
   :dry-run       {:coerce :boolean}
   :json          {:coerce :boolean}
   :verbose       {:alias :v :coerce :boolean}
   :help          {:alias :h :coerce :boolean}})

(defn- parse-argv
  "Manually split argv into option tokens (anything starting with - or --,
   consuming an attached =value or the following token as its value) and
   positional file arguments. Using a hand-rolled pass instead of
   babashka.cli's collecting parser because :collect greedily swallows
   following positional args when options and files are interleaved."
  [argv]
  (loop [args argv, opt-tokens [], files []]
    (if-let [a (first args)]
      (cond
        (str/starts-with? a "--")
        (if (str/includes? a "=")
          (recur (rest args) (conj opt-tokens a) files)
          (let [flag (subs a 2)
                boolean-flag? (contains? #{"no-filename-tag" "dry-run" "json" "verbose" "help"} flag)]
            (if boolean-flag?
              (recur (rest args) (conj opt-tokens a) files)
              (recur (rest (rest args)) (conj opt-tokens a (second args)) files))))

        (and (str/starts-with? a "-") (not= a "-") (> (count a) 1) (not (Character/isDigit (.charAt ^String a 1))))
        (let [flag (subs a 1)
              boolean-flag? (contains? #{"v" "h"} flag)]
          (if boolean-flag?
            (recur (rest args) (conj opt-tokens a) files)
            (recur (rest (rest args)) (conj opt-tokens a (second args)) files)))

        :else
        (recur (rest args) opt-tokens (conj files a)))
      {:opt-tokens opt-tokens :files files})))

(defn- sha256-file
  "Lowercase hex SHA-256 of `path`'s bytes. Matches grout.media.hash/sha256-file
   (and plain sha256sum) so dedup lookups agree with the server."
  [path]
  (let [md  (MessageDigest/getInstance "SHA-256")
        buf (byte-array 8192)]
    (with-open [in (java.io.FileInputStream. (fs/file path))]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update md buf 0 n)
            (recur)))))
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) (.digest md)))))

(defn- split-tags [s]
  (when (some? s)
    (->> (str/split s #",")
         (map str/trim)
         (remove str/blank?))))

(defn- base-url [opts]
  (let [server (or (:server opts) (System/getenv "GROUT_URL"))]
    (when (str/blank? server)
      (binding [*out* *err*]
        (println "error: no Grout server given (pass --server, -s, or set GROUT_URL)"))
      (System/exit 2))
    (str/replace server #"/+$" "")))

(defn- json-headers [] {"Content-Type" "application/json" "Accept" "application/json"})

(defn- http-get [url verbose?]
  (when verbose? (binding [*out* *err*] (println "GET" url)))
  (let [resp (http/get url {:headers {"Accept" "application/json"} :throw false})]
    (assoc resp :json (when (seq (:body resp)) (json/parse-string (:body resp) true)))))

(defn- http-post [url body verbose?]
  (when verbose? (binding [*out* *err*] (println "POST" url (json/generate-string body))))
  (let [resp (http/post url {:headers (json-headers)
                             :body (json/generate-string body)
                             :throw false})]
    (assoc resp :json (when (seq (:body resp)) (json/parse-string (:body resp) true)))))

(defn- by-hash [server hash verbose?]
  (http-get (str server "/grout/by-hash/" hash) verbose?))

(defn- add-tag! [server id tag verbose?]
  (http-post (str server "/grout/media/" id "/tags") {:tag tag} verbose?))

(defn- intake! [server req verbose?]
  (http-post (str server "/grout/media") req verbose?))

(defn- result! [json? m]
  (if json?
    (println (json/generate-string m))
    (let [{:keys [file status id action tags error]} m]
      (if error
        (println (str file ": ERROR " error))
        (println (str file ": " (name action)
                      " id=" id
                      " status=" status
                      " tags=" (str/join "," tags)))))))

(defn- process-file! [server opts tags file]
  (if-not (fs/exists? file)
    {:file file :error "file not found"}
    (let [abs-path (str (fs/canonicalize file))
          filename-tag (str "filename:" (fs/file-name file))
          all-tags (vec (distinct (cond-> tags
                                     (not (:no-filename-tag opts)) (conj filename-tag))))
          hash (sha256-file file)
          verbose? (:verbose opts)
          existing (by-hash server hash verbose?)]
      (cond
        (= 200 (:status existing))
        (let [id (:id (:json existing))]
          (if (:dry-run opts)
            {:file file :action :would-retag :id id :status 200 :tags all-tags :hash hash}
            (let [current (set (:tags (:json existing)))
                  to-add (remove current all-tags)]
              (doseq [tag to-add]
                (let [resp (add-tag! server id tag verbose?)]
                  (when-not (#{200 201} (:status resp))
                    (throw (ex-info (str "failed to add tag " tag)
                                    {:status (:status resp) :body (:body resp)})))))
              {:file file :action :retagged :id id :status 200
               :tags (vec (distinct (concat current all-tags))) :hash hash})))

        (= 404 (:status existing))
        (if (:dry-run opts)
          {:file file :action :would-upload :status 404 :tags all-tags :hash hash}
          (let [req (cond-> {:path abs-path
                             :kind (:kind opts)
                             :tags all-tags
                             :source (:source opts)}
                      (:channel opts)     (assoc :channel (:channel opts))
                      (:source-url opts)  (assoc :source-url (:source-url opts))
                      (:name opts)        (assoc :name (:name opts))
                      (:description opts) (assoc :description (:description opts)))
                resp (intake! server req verbose?)]
            (if (#{200 201} (:status resp))
              {:file file :action (if (= 201 (:status resp)) :uploaded :deduplicated)
               :id (:id (:json resp)) :status (:status resp)
               :tags (:tags (:json resp)) :hash hash}
              {:file file :error (str "intake failed: " (or (:error (:json resp)) (:body resp)))
               :status (:status resp)})))

        :else
        {:file file :error (str "unexpected by-hash status " (:status existing))}))))

(defn -main [argv]
  (let [{:keys [opt-tokens files]} (parse-argv argv)
        {:keys [opts]} (cli/parse-args opt-tokens {:spec cli-spec})]
    (cond
      (:help opts)
      (println usage)

      (empty? files)
      (do (println usage)
          (binding [*out* *err*] (println "error: no media files given"))
          (System/exit 2))

      :else
      (let [server (base-url opts)
            tags (vec (distinct (concat (:tag opts []) (split-tags (:tags opts)))))
            failures (atom 0)]
        (doseq [file files]
          (let [result (try
                         (process-file! server opts tags file)
                         (catch Exception e
                           {:file file :error (or (ex-message e) (str e))}))]
            (when (:error result) (swap! failures inc))
            (result! (:json opts) result)))
        (when (pos? @failures)
          (System/exit 1))))))

(-main *command-line-args*)
