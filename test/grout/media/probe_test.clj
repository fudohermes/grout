(ns grout.media.probe-test
  "Unit tests for ffprobe JSON parsing, stream planning and the
   transcode-permit gate. ffprobe/ffmpeg are not invoked - clojure.java.shell/sh
   and probe/run-ffmpeg! are stubbed."
  (:require [clojure.test :refer [deftest is testing]]
            [grout.media.probe :as probe]))

(def ^:private profile probe/default-profile)

(defn- probe-result
  "A probe result assembled from optional overrides over the profile."
  [& {:as overrides}]
  (merge {:vcodec    (:vcodec profile)
          :pix-fmt   (:pix-fmt profile)
          :acodec    (:acodec profile)
          :sample-rate (:sample-rate profile)
          :channels  (:channels profile)
          :duration-ms 60000}
         overrides))

(deftest probe-parses-ffprobe-json
  (let [json (str "{\"format\":{\"duration\":\"12.5\"},\"streams\":["
                  "{\"codec_type\":\"video\",\"codec_name\":\"h264\",\"width\":1920,\"height\":1080,\"pix_fmt\":\"yuv420p\"},"
                  "{\"codec_type\":\"audio\",\"codec_name\":\"aac\",\"sample_rate\":\"48000\",\"channels\":2}]}")]
    (with-redefs-fn {#'clojure.java.shell/sh
                     (fn [& _] {:exit 0 :out json :err ""})}
      (fn []
        (let [pr (probe/probe "/tmp/does-not-matter.mp4")]
          (is (= 12500 (:duration-ms pr)))
          (is (= "h264" (:vcodec pr)))
          (is (= "yuv420p" (:pix-fmt pr)))
          (is (= "aac" (:acodec pr)))
          (is (= 48000 (:sample-rate pr)))
          (is (= 2 (:channels pr))))))))

(deftest probe-throws-on-nonzero-exit
  (with-redefs-fn {#'clojure.java.shell/sh (fn [& _] {:exit 1 :out "" :err "boom"})}
    (fn []
      (is (thrown-with-msg? Exception #"ffprobe failed" (probe/probe "/x"))))))

(deftest conforms-accepts-profile-match
  (is (probe/conforms? (probe-result) profile)))

(deftest conforms-rejects-mismatches
  (is (not (probe/conforms? (probe-result :acodec "mp3") profile)) "audio codec")
  (is (not (probe/conforms? (probe-result :sample-rate 44100) profile)) "sample rate")
  (is (not (probe/conforms? (probe-result :channels 1) profile)) "channels")
  (is (not (probe/conforms? (probe-result :vcodec "mpeg4") profile)) "video codec")
  (is (not (probe/conforms? (probe-result :pix-fmt "yuv422p") profile)) "pix fmt"))

(deftest stream-plan-picks-cheapest-correct-path
  (testing "fully conforming file: copy both streams"
    (is (= {:video :copy :audio :copy}
           (probe/stream-plan (probe-result) profile))))
  (testing "audio-only mismatch: copy video, transcode audio (the common YouTube-rip case)"
    (is (= {:video :copy :audio :transcode}
           (probe/stream-plan (probe-result :sample-rate 44100) profile))))
  (testing "video mismatch: transcode video regardless of audio state"
    (is (= {:video :transcode :audio :copy}
           (probe/stream-plan (probe-result :vcodec "mpeg4") profile))))
  (testing "no audio stream counts as audio-conforming"
    (is (= {:video :copy :audio :copy}
           (probe/stream-plan (probe-result :acodec nil) profile)))))

(deftest normalize-args-audio-fix-copies-video-reencodes-audio
  ;; :video :copy resolves no accel, so the video stream must be stream-copied
  ;; (-c:v copy) while audio still re-encodes to the profile. normalize-args is
  ;; private but exercised here via its var - the arg vector is the contract.
  (with-redefs-fn {#'grout.media.accel/resolve-accel (fn [_] nil)}
    (fn []
      (let [args (#'probe/normalize-args "/in.mp4" "/out.mp4" profile
                                         {:video :copy :audio :transcode})]
        (is (some #{"-c:v"} args))
        (is (= "copy" (nth args (+ (.indexOf (vec args) "-c:v") 1))))
        (is (some #{"-c:a" "aac"} args))
        ;; The audio args must re-encode to the profile rate.
        (is (= ["-c:a" "aac" "-b:a" "192k" "-ar" "48000" "-ac" "2"]
               (->> args
                    (drop-while #(not= "-c:a" %))
                    (take 8)
                    vec)))))))

(deftest only-video-encodes-take-a-transcode-permit
  (let [permits (java.util.concurrent.Semaphore. 1)
        profile (assoc probe/default-profile :transcode-permits permits)
        seen    (atom [])]
    (with-redefs-fn {#'probe/run-ffmpeg!
                     ;; 3 args since the work-dir change: args timeout-secs work-dir
                     (fn [_ _ _]
                       (swap! seen conj (.availablePermits permits))
                       {:exit 0 :err ""})}
      (fn []
        (#'probe/run-normalize! [] profile {:video :transcode :audio :copy} 1000 nil)
        (#'probe/run-normalize! [] profile {:video :copy :audio :transcode} 1000 nil)))
    (is (= [0 1] @seen) "the permit is held during a video encode, not during a copy")
    (is (= 1 (.availablePermits permits)) "the permit is released afterwards")))

(deftest ffmpeg-timeout-floor-and-scale
  (is (= (* 30 60) (probe/ffmpeg-timeout-secs nil)))
  (is (= (* 30 60) (probe/ffmpeg-timeout-secs 60000)) "short clips get the floor")
  (is (= (* 5 3600) (probe/ffmpeg-timeout-secs (* 3600 1000))) "an hour of media gets 5h"))

(deftest run-ffmpeg-creates-stderr-in-work-dir
  ;; The work-dir contract: when a work-dir is given, the stderr temp file must
  ;; be created THERE. java.io.tmpdir is hardcoded to /tmp on JDKs before 22
  ;; (JDK-8318157), and minimal containers have no /tmp - so an explicit dir
  ;; that the caller controls is the fix. Run a real trivial binary so the
  ;; temp-file lifecycle executes end to end.
  (let [work-dir (str (System/getProperty "user.home") "/.grout-test-tmp")]
    (.mkdirs (java.io.File. work-dir))
    (try
      (let [{:keys [exit]} (#'probe/run-ffmpeg! ["true"] 10 work-dir)]
        (is (zero? exit))
        (testing "no stray grout-ffmpeg temp files left behind"
          (is (empty? (filter #(re-find #"grout-ffmpeg" %)
                              (.list (java.io.File. work-dir)))))))
      (finally
        (doseq [f (.listFiles (java.io.File. work-dir))]
          (.delete f))
        (.delete (java.io.File. work-dir))))))
