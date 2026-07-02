(ns grout.tunabrain
  "Client for Tunabrain, the LLM/AI gateway (GROUT.md §9). Grout holds no model
   keys and stays dumb about models — Tunabrain owns the OpenRouter key.

   This client targets an OpenAI-compatible chat-completions endpoint. The exact
   Tunabrain surface (path/auth) should be confirmed against TS's existing
   Tunabrain client and adjusted here; the request path is configurable."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(defn client
  "Build a Tunabrain client from config."
  [{:keys [endpoint model timeout-ms chat-path]}]
  {:endpoint   endpoint
   :model      (or model "openrouter/auto")
   :timeout-ms (or timeout-ms 30000)
   :chat-path  (or chat-path "/v1/chat/completions")})

(def ^:private system-prompt
  (str "You tag short filler/bumper video clips for a TV playout system. "
       "Given the context, respond ONLY with a compact JSON object with keys "
       "\"name\" (a short title), \"description\" (one sentence), and \"tags\" "
       "(an array of lowercase short tags). No prose, no code fences."))

(defn- build-user-prompt [{:keys [name channel duration-ms tags transcript hint]}]
  (->> [(when name (str "Current name: " name))
        (when channel (str "Channel: " channel))
        (when duration-ms (str "Duration (ms): " duration-ms))
        (when (seq tags) (str "Existing tags: " (str/join ", " tags)))
        (when hint (str "Hint: " hint))
        (when transcript (str "Transcript excerpt: " transcript))]
       (remove nil?)
       (str/join "\n")))

(defn- parse-json-lenient
  "Extract the first JSON object from a model response, tolerating code fences
   and surrounding prose."
  [s]
  (when s
    (let [t     (str/replace (str/trim s) #"(?s)^```(?:json)?\s*|\s*```$" "")
          start (.indexOf t "{")
          end   (.lastIndexOf t "}")]
      (when (and (>= start 0) (> end start))
        (try (json/parse-string (subs t start (inc end)) true)
             (catch Exception _ nil))))))

(defn suggest-metadata
  "Ask Tunabrain to derive {:name :description :tags} for a media item from
   `context` (:name :channel :duration-ms :tags :transcript :hint). Returns the
   suggestion map, or nil on any failure (caller decides what to do)."
  [{:keys [endpoint model timeout-ms chat-path]} context]
  (try
    (let [resp (http/post (str endpoint chat-path)
                          {:content-type       :json
                           :accept             :json
                           :as                 :json
                           :socket-timeout     timeout-ms
                           :connection-timeout timeout-ms
                           :throw-exceptions   false
                           :form-params
                           {:model    model
                            :messages [{:role "system" :content system-prompt}
                                       {:role "user" :content (build-user-prompt context)}]
                            :temperature 0.2}})]
      (if (<= 200 (:status resp) 299)
        (when-let [parsed (parse-json-lenient
                           (get-in resp [:body :choices 0 :message :content]))]
          {:name        (:name parsed)
           :description (:description parsed)
           :tags        (some->> (:tags parsed)
                                 (map str)
                                 (map str/trim)
                                 (remove str/blank?)
                                 vec)})
        (do (log/warn "Tunabrain enrichment returned non-2xx"
                      {:status (:status resp)})
            nil)))
    (catch Exception e
      (log/warn e "Tunabrain enrichment call failed")
      nil)))
