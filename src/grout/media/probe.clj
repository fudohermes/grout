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
  "PV playout profile - mirrors Tunarr Scheduler's bumper profile.

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

(defn conforms-video?
  "True when the video stream already matches the profile's video codec and
  pixel format."
  [probe-result profile]
  (and (= (:vcodec probe-result) (:vcodec profile))
       (= (:pix-fmt probe-result) (:pix-fmt profile))))

(defn conforms-audio?
  "True when the audio stream already matches the profile's audio codec,
  sample rate and channel count."
  [probe-result profile]
  (and (= (:acodec probe-result) (:acodec profile))
       (= (:sample-rate probe-result) (:sample-rate profile))
       (= (:channels probe-result) (:channels profile))))

(defn conforms?
  "True when a probe result already matches the playout profile's codecs,
  pixel format, sample rate and channel count."
  [probe-result profile]
  (and (conforms-video? probe-result profile)
       (conforms-audio? probe-result profile)))

(defn normalize-mode
  "Which ffmpeg path `probe-result` needs against `profile`:

     :remux      fully conforming - stream-copy everything, just add faststart
     :audio-fix  video conforms but audio does not - copy the video stream and
                 re-encode audio only. Common for web rips (h264/yuv420p video
                 is already playout-ready but audio is 44.1kHz); re-encoding the
                 video for an audio-only mismatch is minutes of GPU/CPU time
                 for zero visual benefit, vs seconds for the audio.
     :transcode  video does not conform - full transcode via `transcode-args`"
  [probe-result profile]
  (cond
    (conforms? probe-result profile) :remux
    (conforms-video? probe-result profile) :audio-fix
    :else :transcode))

(defn- with-ext [path ext]
  (let [dot (.lastIndexOf ^String path ".")]
    (str (if (pos? dot) (subs path 0 dot) path) ext)))

;; Transcode to the playout profile, offloading video encode to the GPU when a
;; backend is available (archive/GROUT.md §8). `accel/resolve-accel` downgrades to
;; software on CPU-only hosts, so this same arg vector is correct everywhere.
;; The hardware paths add pre-`-i` device flags and a `-vf` hwupload; software
;; keeps the original `-pix_fmt` conversion (GPU frames are formatted by the
;; filter instead). Audio + faststart are backend-independent.
(defn- transcode-args [in out profile]
  (let [requested (:accel profile)
        accel     (accel/resolve-accel requested)
        vf        (accel/video-filter accel)
        venc      (accel/video-encode-args accel (:vcodec profile))]
    ;; The concrete encoder (e.g. h264_nvenc vs libx264) is the actionable bit
    ;; when eyeballing whether a given intake actually used the GPU; it's the
    ;; second element of venc ("-c:v" <encoder> ...).
    (log/info "Transcoding media"
              {:in in :out out
               :requested-accel (or requested :auto)
               :accel accel
               :encoder (second venc)})
    (-> [ffmpeg-bin "-y"]
        (into (accel/input-args accel))
        (into ["-i" in])
        (cond-> vf (into ["-vf" vf]))
        (into venc)
        (cond-> (= accel :none) (into ["-pix_fmt" (:pix-fmt profile)]))
        (into ["-c:a" "aac" "-b:a" (:audio-bitrate profile)
               "-ar" (str (:sample-rate profile))
               "-ac" (str (:channels profile))
               "-movflags" "+faststart"
               out]))))

;; Video-conforming / audio-offending files keep their video bit-for-bit and
;; re-encode audio only. Seconds of work instead of a full re-encode, and no
;; generational loss on the video stream.
(defn- audio-fix-args [in out profile]
  [ffmpeg-bin "-y" "-i" in
   "-map" "0:v:0" "-map" "0:a:0?"
   "-c:v" "copy"
   "-c:a" "aac" "-b:a" (:audio-bitrate profile)
   "-ar" (str (:sample-rate profile))
   "-ac" (str (:channels profile))
   "-movflags" "+faststart"
   out])

;; Conforming files only need the moov atom moved to the front for faststart;
;; a stream copy is cheap and lossless.
(defn- remux-args [in out]
  [ffmpeg-bin "-y" "-i" in "-c" "copy" "-movflags" "+faststart" out])

(defn- mode->args [mode in out profile]
  (case mode
    :remux (remux-args in out)
    :audio-fix (audio-fix-args in out profile)
    :transcode (transcode-args in out profile)))

(defn normalize-to!
  "Normalize `in` into `out` (creating parent dirs) WITHOUT mutating `in`:
   full transcode for off-profile video, video-copy audio-only re-encode when
   only audio is off-profile, stream-copy for conforming media - always with
   faststart. Returns {:path out :probe fresh-probe :normalized bool}. Used by
   content-addressed intake, which must leave the caller's source untouched."
  ([in out profile] (normalize-to! in out profile nil))
  ([in out profile probe-result]
   (io/make-parents out)
   (let [pr          (or probe-result (probe in))
         mode        (normalize-mode pr profile)
         conforming? (= mode :remux)
         tmp         (str out ".tmp-" (System/currentTimeMillis) ".mp4")
         args        (mode->args mode in tmp profile)
         _           (log/info "Normalizing media"
                               {:in in :out out :conforming conforming? :mode mode})
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
         mode        (normalize-mode pr profile)
         conforming? (= mode :remux)
         out         (with-ext path ".mp4")
         tmp         (str out ".tmp-" (System/currentTimeMillis) ".mp4")
         args        (mode->args mode path tmp profile)
         _           (log/info "Normalizing media"
                               {:path path :conforming conforming? :mode mode :out out})
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
