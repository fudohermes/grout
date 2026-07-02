(ns grout.http.schemas
  "Malli schemas for request/response coercion and OpenAPI generation.

   Response bodies use kebab-case keys to match the service-wide JSON
   convention (see grout.http.middleware). Query parameters use the snake_case
   names from the GROUT.md query example (min_ms, max_ms).")

(def APIError
  [:map [:error :string]])

(def Health
  [:map
   [:status [:enum "ok" "degraded"]]
   [:database [:enum "ok" "error"]]
   [:version {:optional true} [:maybe :string]]])

(def Version
  [:map
   [:git-commit {:optional true} [:maybe :string]]
   [:git-timestamp {:optional true} [:maybe :string]]
   [:version {:optional true} [:maybe :string]]])

;; --- Media -----------------------------------------------------------------

(def Media
  "Full media representation returned by GET/PATCH /grout/media/:id."
  [:map
   [:id :uuid]
   [:kind :string]
   [:path :string]
   [:name {:optional true} [:maybe :string]]
   [:description {:optional true} [:maybe :string]]
   [:channel {:optional true} [:maybe :string]]
   [:tags [:vector :string]]
   [:duration-ms :int]
   [:width {:optional true} [:maybe :int]]
   [:height {:optional true} [:maybe :int]]
   [:vcodec {:optional true} [:maybe :string]]
   [:acodec {:optional true} [:maybe :string]]
   [:source {:optional true} [:maybe :string]]
   [:source-url {:optional true} [:maybe :string]]
   [:enriched :boolean]
   [:stream-url :string]
   [:created-at {:optional true} [:maybe :string]]
   [:superseded-at {:optional true} [:maybe :string]]])

(def MediaSummary
  "Compact media representation returned by the query endpoint. Includes `path`
   for by-path streaming and `stream-url` for the HTTP fallback."
  [:map
   [:id :uuid]
   [:name {:optional true} [:maybe :string]]
   [:duration-ms :int]
   [:path :string]
   [:stream-url :string]
   [:vcodec {:optional true} [:maybe :string]]
   [:acodec {:optional true} [:maybe :string]]
   [:tags [:vector :string]]])

(def MediaQueryResult
  [:map
   [:count :int]
   [:items [:vector MediaSummary]]])

(def MediaQueryParams
  "Query-string parameters for GET /grout/media."
  [:map
   [:channel {:optional true} :string]
   [:tags {:optional true} :string]        ; comma-separated, AND semantics
   [:min_ms {:optional true} :int]
   [:max_ms {:optional true} :int]
   [:kind {:optional true} :string]
   [:limit {:optional true} [:int {:min 1}]]
   [:random {:optional true} :boolean]])

(def MediaPatch
  "Mutable metadata fields for PATCH /grout/media/:id."
  [:map
   [:name {:optional true} [:maybe :string]]
   [:description {:optional true} [:maybe :string]]
   [:channel {:optional true} [:maybe :string]]
   [:tags {:optional true} [:vector :string]]])

(def IdPath
  [:map [:id :uuid]])

(def DeleteQuery
  [:map [:hard {:optional true} :boolean]])

(def DeleteResult
  [:map
   [:deleted :boolean]
   [:hard :boolean]])

(def TagList
  [:map [:tags [:vector :string]]])

(def TagAddRequest
  [:map [:tag :string]])
