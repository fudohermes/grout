(ns grout.media.probe-test
  "Unit tests for ffprobe JSON parsing and profile conformance. ffprobe/ffmpeg
   are not invoked — clojure.java.shell/sh is stubbed."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.shell :as sh]
            [grout.media.probe :as probe]))

(def ^:private ffprobe-json
  (str "{\"format\":{\"duration\":\"65.400000\"},"
       "\"streams\":[{\"codec_type\":\"video\",\"codec_name\":\"h264\","
       "\"width\":1920,\"height\":1080,\"pix_fmt\":\"yuv420p\"},"
       "{\"codec_type\":\"audio\",\"codec_name\":\"aac\","
       "\"sample_rate\":\"48000\",\"channels\":2}]}"))

(deftest probe-parses-ffprobe-json
  (with-redefs [sh/sh (fn [& _] {:exit 0 :out ffprobe-json :err ""})]
    (let [r (probe/probe "/x.mp4")]
      (is (= 65400 (:duration-ms r)))
      (is (= 1920 (:width r)))
      (is (= 1080 (:height r)))
      (is (= "h264" (:vcodec r)))
      (is (= "aac" (:acodec r)))
      (is (= "yuv420p" (:pix-fmt r)))
      (is (= 48000 (:sample-rate r)))
      (is (= 2 (:channels r))))))

(deftest probe-throws-on-ffprobe-failure
  (with-redefs [sh/sh (fn [& _] {:exit 1 :out "" :err "boom"})]
    (is (thrown? clojure.lang.ExceptionInfo (probe/probe "/x.mp4")))))

(deftest conforms-detects-profile
  (let [good {:vcodec "h264" :pix-fmt "yuv420p" :acodec "aac"
              :sample-rate 48000 :channels 2}]
    (is (probe/conforms? good probe/default-profile))
    (is (not (probe/conforms? (assoc good :acodec "mp3") probe/default-profile)))
    (is (not (probe/conforms? (assoc good :sample-rate 44100) probe/default-profile)))
    (is (not (probe/conforms? (assoc good :pix-fmt "yuv444p") probe/default-profile)))))

(deftest software-transcode-args-unchanged
  ;; Guards that a software (`:accel :none`) transcode still emits the original
  ;; command: bare libx264 + -pix_fmt, no hardware device/hwaccel/-vf flags.
  ;; This is the fallback path on CPU-only hosts, so it must not regress.
  (let [normalize-args #'probe/normalize-args
        args (normalize-args "in.mkv" "out.mp4"
                             (assoc probe/default-profile :accel :none)
                             {:video :transcode :audio :transcode})]
    (is (= ["-c:v" "libx264" "-pix_fmt" "yuv420p"]
           (->> args (drop-while #(not= % "in.mkv")) rest (take 4)))
        "video encode is unchanged libx264 + pixfmt")
    (is (not-any? #{"-hwaccel" "-init_hw_device" "-vf"} args)
        "no hardware flags on the software path")
    (is (= "+faststart" (nth args (inc (.indexOf ^java.util.List args "-movflags"))))
        "faststart preserved")))

(def ^:private conforming-probe
  {:vcodec "h264" :pix-fmt "yuv420p" :acodec "aac"
   :sample-rate 48000 :channels 2})

(deftest stream-plan-judges-streams-independently
  (let [plan #(probe/stream-plan % probe/default-profile)]
    (is (= {:video :copy :audio :copy} (plan conforming-probe)))
    (is (= {:video :copy :audio :transcode} (plan (assoc conforming-probe :acodec "ac3"))))
    (is (= {:video :copy :audio :transcode} (plan (assoc conforming-probe :channels 6))))
    (is (= {:video :copy :audio :transcode} (plan (assoc conforming-probe :sample-rate 44100))))
    (is (= {:video :transcode :audio :copy} (plan (assoc conforming-probe :vcodec "hevc"))))
    (is (= {:video :transcode :audio :copy} (plan (assoc conforming-probe :pix-fmt "yuv420p10le"))))
    (is (= {:video :transcode :audio :transcode}
           (plan (assoc conforming-probe :vcodec "mpeg4" :acodec "mp3"))))
    (is (= {:video :copy :audio :copy}
           (plan (dissoc conforming-probe :acodec :sample-rate :channels)))
        "a silent file has no audio to encode")))

(defn- args-after-input [args]
  (->> args (drop-while #(not= % "in.mkv")) rest vec))

(deftest audio-only-fix-copies-video
  ;; The common off-profile case: video already matches, audio doesn't. The
  ;; video must be stream-copied with no GPU/device setup at all, and only
  ;; audio re-encoded. accel is :auto here to prove no backend is resolved.
  (let [normalize-args #'probe/normalize-args
        args (normalize-args "in.mkv" "out.mp4" probe/default-profile
                             {:video :copy :audio :transcode})]
    (is (= ["-c:v" "copy" "-c:a" "aac" "-b:a" "192k" "-ar" "48000" "-ac" "2"
            "-movflags" "+faststart" "out.mp4"]
           (args-after-input args)))
    (is (not-any? #{"-hwaccel" "-init_hw_device" "-vf" "-pix_fmt"} args))))

(deftest video-only-fix-copies-audio
  (let [normalize-args #'probe/normalize-args
        args (normalize-args "in.mkv" "out.mp4"
                             (assoc probe/default-profile :accel :none)
                             {:video :transcode :audio :copy})]
    (is (= ["-c:v" "libx264" "-pix_fmt" "yuv420p" "-c:a" "copy"
            "-movflags" "+faststart" "out.mp4"]
           (args-after-input args)))))

(deftest conforming-file-is-remuxed
  (let [normalize-args #'probe/normalize-args
        args (normalize-args "in.mkv" "out.mp4" probe/default-profile
                             {:video :copy :audio :copy})]
    (is (= ["-c" "copy" "-movflags" "+faststart" "out.mp4"]
           (args-after-input args)))))

(deftest ffmpeg-timeout-scales-with-duration
  (is (= (* 30 60) (probe/ffmpeg-timeout-secs nil)))
  (is (= (* 30 60) (probe/ffmpeg-timeout-secs 60000)) "short clips get the floor")
  (is (= (* 5 3600) (probe/ffmpeg-timeout-secs (* 3600 1000))) "an hour of media gets 5h"))

(deftest only-video-encodes-take-a-transcode-permit
  (let [run-normalize! #'probe/run-normalize!
        permits (java.util.concurrent.Semaphore. 1)
        profile (assoc probe/default-profile :transcode-permits permits)
        seen    (atom [])]
    (with-redefs-fn {#'probe/run-ffmpeg!
                     (fn [_ _] (swap! seen conj (.availablePermits permits)) {:exit 0 :err ""})}
      (fn []
        (run-normalize! [] profile {:video :transcode :audio :copy} 1000)
        (run-normalize! [] profile {:video :copy :audio :transcode} 1000)))
    (is (= [0 1] @seen) "the permit is held during a video encode, not during a copy")
    (is (= 1 (.availablePermits permits)) "the permit is released afterwards")))
