(ns grout.tunabrain-test
  (:require [clojure.test :refer [deftest is]]
            [clj-http.client :as http]
            [grout.tunabrain :as tb]))

(defn- chat-resp [content]
  {:status 200 :body {:choices [{:message {:content content}}]}})

(def ^:private cl (tb/client {:endpoint "http://tunabrain"}))

(deftest suggest-parses-clean-json
  (with-redefs [http/post (fn [_ _]
                            (chat-resp (str "{\"name\":\"Sunset Ident\","
                                            "\"description\":\"A calm sunset bumper.\","
                                            "\"tags\":[\"calm\",\"sunset\"]}")))]
    (let [r (tb/suggest-metadata cl {:name "clip"})]
      (is (= "Sunset Ident" (:name r)))
      (is (= "A calm sunset bumper." (:description r)))
      (is (= ["calm" "sunset"] (:tags r))))))

(deftest suggest-tolerates-code-fences-and-trims-tags
  (with-redefs [http/post (fn [_ _]
                            (chat-resp "```json\n{\"name\":\"N\",\"tags\":[\"a\",\" b \",\"\"]}\n```"))]
    (let [r (tb/suggest-metadata cl {})]
      (is (= "N" (:name r)))
      (is (= ["a" "b"] (:tags r)) "trims and drops blanks"))))

(deftest suggest-returns-nil-on-non-2xx
  (with-redefs [http/post (fn [_ _] {:status 500 :body {}})]
    (is (nil? (tb/suggest-metadata cl {})))))

(deftest suggest-returns-nil-on-exception
  (with-redefs [http/post (fn [_ _] (throw (RuntimeException. "boom")))]
    (is (nil? (tb/suggest-metadata cl {})))))
