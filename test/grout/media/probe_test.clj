(ns grout.media.probe-test
  "Unit tests for ffprobe JSON parsing and profile conformance. ffprobe/ffmpeg
   are not invoked - clojure.java.shell/sh is stubbed."
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

(deftest normalize-mode-picks-cheapest-correct-path
  (let [good {:vcodec "h264" :pix-fmt "yuv420p" :acodec "aac"
              :sample-rate 48000 :channels 2}]
    (is (= :remux (probe/normalize-mode good probe/default-profile)))
    ;; web-rip case: video is playout-ready, audio is 44.1kHz - the expensive
    ;; mistake this test pins is ever sending this back through a full transcode
    (is (= :audio-fix (probe/normalize-mode
                       (assoc good :sample-rate 44100) probe/default-profile)))
    (is (= :audio-fix (probe/normalize-mode
                       (assoc good :acodec "mp3") probe/default-profile)))
    (is (= :transcode (probe/normalize-mode
                       (assoc good :pix-fmt "yuv444p") probe/default-profile)))
    (is (= :transcode (probe/normalize-mode
                       (assoc good :vcodec "hevc") probe/default-profile)))))

(deftest audio-fix-args-copy-video-reencode-audio
  (let [args (#'probe/audio-fix-args "in.mp4" "out.mp4" probe/default-profile)]
    (is (some #{"copy"} args) "video stream is copied, not re-encoded")
    (is (some #{"aac"} args) "audio is re-encoded to aac")
    (is (some #{"48000"} args) "audio resampled to the profile rate")
    (is (= "+faststart" (nth args (inc (.indexOf ^java.util.List args "-movflags"))))
        "faststart preserved")))

(deftest software-transcode-args-unchanged
  ;; Guards that a software (`:accel :none`) transcode still emits the original
  ;; command: bare libx264 + -pix_fmt, no hardware device/hwaccel/-vf flags.
  ;; This is the fallback path on CPU-only hosts, so it must not regress.
  (let [transcode-args #'probe/transcode-args
        args (transcode-args "in.mkv" "out.mp4"
                             (assoc probe/default-profile :accel :none))]
    (is (= ["-c:v" "libx264" "-pix_fmt" "yuv420p"]
           (->> args (drop-while #(not= % "in.mkv")) rest (take 4)))
        "video encode is unchanged libx264 + pixfmt")
    (is (not-any? #{"-hwaccel" "-init_hw_device" "-vf"} args)
        "no hardware flags on the software path")
    (is (= "+faststart" (nth args (inc (.indexOf ^java.util.List args "-movflags"))))
        "faststart preserved")))
