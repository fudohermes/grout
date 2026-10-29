(ns grout.media.probe
  "ffprobe/ffmpeg wrapper for the intake pipeline (archive/GROUT.md §8).

   Extracts technical metadata and normalizes off-profile media to PV's playout
   profile with a faststart moov atom, so PV can seek/stream without a full
   download. Binaries are resolved from FFPROBE_PATH/FFMPEG_PATH (set by the nix
   flake) falling back to the names on PATH."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [grout.media.accel :as accel]
            [taoensso.timbre :as log]))

(def ^:private ffprobe-bin (or (System/getenv "FFPROBE_PATH") "ffprobe"))
(def ^:private ffmpeg-bin (or (System/getenv "FFMPEG_PATH") "ffmpeg"))

(def default-profile
  "PV playout profile — mirrors Tunarr Scheduler's bumper profile.

   `:accel` selects the transcode backend (see `grout.media.accel`): `:auto`
   uses hardware acceleration when the host exposes it and falls back to
   software otherwise, so the same image runs on GPU and CPU-only nodes.
   Override via the profile config / `GROUT_FFMPEG_ACCEL` (`none` forces
   software, e.g. to keep a shared GPU free for live playout)."
  {:vcodec "h264"
   :pix-fmt "yuv420p"
   :acodec "aac"
   :sample-rate 48000
   :channels 2
   :audio-bitrate "192k"
   :accel :auto})

(defn- last-lines [s n]
  (when s (->> (str/split-lines s) (take-last n) (str/join "\n"))))

(defn probe
  "Run ffprobe and return a map with :duration-ms :width :height :vcodec
   :acodec :pix-fmt :sample-rate :channels. Throws ex-info on failure."
  [path]
  (let [{:keys [exit out err]}
        (sh/sh ffprobe-bin "-v" "quiet" "-print_format" "json"
               "-show_format" "-show_streams" path)]
    (when-not (zero? exit)
      (throw (ex-info "ffprobe failed" {:path path :exit exit :err err})))
    (let [data    (json/parse-string out true)
          streams (:streams data)
          v       (first (filter #(= "video" (:codec_type %)) streams))
          a       (first (filter #(= "audio" (:codec_type %)) streams))
          dur-s   (some-> (get-in data [:format :duration]) parse-double)]
      {:duration-ms (when dur-s (long (Math/round (* 1000.0 dur-s))))
       :width       (:width v)
       :height      (:height v)
       :vcodec      (:codec_name v)
       :acodec      (:codec_name a)
       :pix-fmt     (:pix_fmt v)
       :sample-rate (some-> (:sample_rate a) parse-long)
       :channels    (:channels a)})))

(defn video-conforms?
  "True when the probed video stream already matches the profile's codec and
   pixel format, so it can be stream-copied instead of re-encoded."
  [probe-result profile]
  (and (= (:vcodec probe-result) (:vcodec profile))
       (= (:pix-fmt probe-result) (:pix-fmt profile))))

(defn audio-conforms?
  "True when the probed audio stream already matches the profile's codec,
   sample rate and channel count, so it can be stream-copied."
  [probe-result profile]
  (and (= (:acodec probe-result) (:acodec profile))
       (= (:sample-rate probe-result) (:sample-rate profile))
       (= (:channels probe-result) (:channels profile))))

(defn conforms?
  "True when a probe result already matches the playout profile's codecs,
   pixel format, sample rate and channel count."
  [probe-result profile]
  (and (video-conforms? probe-result profile)
       (audio-conforms? probe-result profile)))

(defn stream-plan
  "Per-stream normalize decision: {:video :copy|:transcode
   :audio :copy|:transcode}. Video and audio are judged independently, so a
   file whose only fault is its audio (the common case: h264 with AC-3 or 5.1
   audio) keeps its video bytes and only re-encodes the cheap audio track,
   and vice versa. A file with no audio stream counts as audio-conforming:
   there is nothing to encode."
  [probe-result profile]
  {:video (if (video-conforms? probe-result profile) :copy :transcode)
   :audio (if (or (nil? (:acodec probe-result))
                  (audio-conforms? probe-result profile))
            :copy
            :transcode)})

(defn- with-ext [path ext]
  (let [dot (.lastIndexOf ^String path ".")]
    (str (if (pos? dot) (subs path 0 dot) path) ext)))

(defn- audio-encode-args [profile]
  ["-c:a" "aac" "-b:a" (:audio-bitrate profile)
   "-ar" (str (:sample-rate profile))
   "-ac" (str (:channels profile))])

;; Build the ffmpeg command for a stream plan. A stream marked :copy is passed
;; through untouched; a :transcode video stream goes through the resolved accel
;; backend (archive/GROUT.md §8). `accel/resolve-accel` downgrades to software
;; on CPU-only hosts, so this same arg vector is correct everywhere. The
;; hardware paths add pre-`-i` device flags and a `-vf` hwupload; software
;; keeps the `-pix_fmt` conversion (GPU frames are formatted by the filter
;; instead). Device flags are only added when video is actually encoded, so an
;; audio-only fix never touches the GPU. Faststart is always applied.
(defn- normalize-args [in out profile {:keys [video audio] :as plan}]
  (if (= [:copy :copy] [video audio])
    ;; Conforming files only need the moov atom moved to the front.
    [ffmpeg-bin "-y" "-i" in "-c" "copy" "-movflags" "+faststart" out]
    (let [requested (:accel profile)
          accel     (when (= video :transcode) (accel/resolve-accel requested))
          vf        (when accel (accel/video-filter accel))
          venc      (if accel
                      (accel/video-encode-args accel (:vcodec profile))
                      ["-c:v" "copy"])]
      ;; The concrete encoder (e.g. h264_nvenc vs libx264, or "copy") is the
      ;; actionable bit when eyeballing whether a given intake used the GPU or
      ;; skipped the video encode entirely.
      (log/info "Transcoding media"
                {:in in :out out
                 :plan plan
                 :requested-accel (or requested :auto)
                 :accel accel
                 :encoder (second venc)})
      (-> [ffmpeg-bin "-y"]
          (cond-> accel (into (accel/input-args accel)))
          (into ["-i" in])
          (cond-> vf (into ["-vf" vf]))
          (into venc)
          (cond-> (= accel :none) (into ["-pix_fmt" (:pix-fmt profile)]))
          (into (if (= audio :transcode) (audio-encode-args profile) ["-c:a" "copy"]))
          (into ["-movflags" "+faststart" out])))))

;; Full transcode of both streams; kept as a named entry point for tests.
(defn- transcode-args [in out profile]
  (normalize-args in out profile {:video :transcode :audio :transcode}))

(defn normalize-to!
  "Normalize `in` into `out` (creating parent dirs) WITHOUT mutating `in`:
   re-encodes only the off-profile streams (see `stream-plan`) and stream-copies
   the rest, always with faststart. Returns {:path out :probe fresh-probe :normalized bool}. Used by
   content-addressed intake, which must leave the caller's source untouched."
  ([in out profile] (normalize-to! in out profile nil))
  ([in out profile probe-result]
   (io/make-parents out)
   (let [pr          (or probe-result (probe in))
         plan        (stream-plan pr profile)
         conforming? (= plan {:video :copy :audio :copy})
         tmp         (str out ".tmp-" (System/currentTimeMillis) ".mp4")
         args        (normalize-args in tmp profile plan)
         _           (log/info "Normalizing media"
                               {:in in :out out :conforming conforming? :plan plan})
         {:keys [exit err]} (apply sh/sh args)]
     (when-not (zero? exit)
       (io/delete-file tmp true)
       (throw (ex-info "ffmpeg normalize failed"
                       {:in in :out out :exit exit :err (last-lines err 20)})))
     (let [tmp-f (io/file tmp)
           out-f (io/file out)]
       (when-not (.renameTo tmp-f out-f)
         (io/copy tmp-f out-f)
         (io/delete-file tmp true)))
     {:path out :probe (probe out) :normalized (not conforming?)})))

(defn normalize!
  "Ensure `path` conforms to `profile` and is faststart-enabled, rewriting the
   file to `<base>.mp4` (removing the original if the name changed). Returns
   {:path final-path :probe fresh-probe :normalized bool}."
  ([path profile] (normalize! path profile nil))
  ([path profile probe-result]
   (let [pr          (or probe-result (probe path))
         plan        (stream-plan pr profile)
         conforming? (= plan {:video :copy :audio :copy})
         out         (with-ext path ".mp4")
         tmp         (str out ".tmp-" (System/currentTimeMillis) ".mp4")
         args        (normalize-args path tmp profile plan)
         _           (log/info "Normalizing media"
                               {:path path :conforming conforming? :plan plan :out out})
         {:keys [exit err]} (apply sh/sh args)]
     (when-not (zero? exit)
       (io/delete-file tmp true)
       (throw (ex-info "ffmpeg normalize failed"
                       {:path path :exit exit :err (last-lines err 20)})))
     (let [tmp-f (io/file tmp)
           out-f (io/file out)]
       ;; If the container/extension changed, drop the original first.
       (when (and (not= path out) (.exists (io/file ^String path)))
         (io/delete-file path true))
       (when-not (.renameTo tmp-f out-f)
         (io/copy tmp-f out-f)
         (io/delete-file tmp true)))
     {:path out :probe (probe out) :normalized (not conforming?)})))
