(ns metabase.search.core
  "NOT the API namespace for the search module!! See [[metabase.search]] instead."
  (:require
   [metabase.analytics.core :as analytics]
   [metabase.search.appdb.core :as search.engines.appdb]
   [metabase.search.config :as search.config]
   [metabase.search.engine :as search.engine]
   [metabase.search.impl :as search.impl]
   [metabase.search.in-place.legacy :as search.legacy]
   [metabase.search.ingestion :as search.ingestion]
   [metabase.search.spec :as search.spec]
   [metabase.search.util :as search.util]
   [metabase.util.log :as log]
   [potemkin :as p]))

(comment
  ;; Make sure to import all the engine implementations. In future this can happen automatically, as per drivers.
  ;;
  ;; TODO -- maybe engine loading should be moved to [[metabase.search.init]] instead
  search.engine/keep-me
  search.engines.appdb/keep-me
  search.legacy/keep-me

  search.config/keep-me
  search.impl/keep-me)

(p/import-vars
 [search.config
  SearchableModel]

 [search.engine
  model-set]

 [search.impl
  search
  ;; We could avoid exposing this by wrapping `query-model-set` and `search` with it.
  search-context]

 [search.ingestion
  bulk-ingest!
  searchable-value-trim-sql]

 [search.spec
  define-spec])

(defmethod analytics/known-labels :metabase-search/index
  [_]
  (for [model (keys (search.spec/specifications))]
    {:model model}))

(defmethod analytics/known-labels :metabase-search/engine-default
  [_]
  (analytics/known-labels :metabase-search/engine-active))

(defmethod analytics/known-labels :metabase-search/engine-active
  [_]
  (for [e (search.engine/known-engines)]
    {:engine (name e)}))

(defmethod analytics/initial-value :metabase-search/engine-default
  [_ {:keys [engine]}]
  (if (= engine (name (search.impl/default-engine))) 1 0))

(defmethod analytics/initial-value :metabase-search/engine-active
  [_ {:keys [engine]}]
  (if (search.engine/supported-engine? (keyword "search.engine" engine)) 1 0))

(defn supports-index?
  "Does this instance support a search index, of any sort?"
  []
  (seq (search.engine/active-engines)))

(defn init-index!
  "Ensure there is an index ready to be populated."
  [& {:as opts}]
  (when (supports-index?)
    (log/info "Initializing search indexes")
    ;; If there are multiple indexes, return the peak inserted for each type. In practice, they should all be the same.
    (reduce (partial merge-with max)
            nil
            (for [e (search.engine/active-engines)]
              (search.engine/init! e opts)))))

(defn reindex!
  "Populate a new index, and make it active. Simultaneously updates the current index."
  [& {:as opts}]
  ;; If there are multiple indexes, return the peak inserted for each type. In practice, they should all be the same.
  (when (supports-index?)
    (reduce (partial merge-with max)
            nil
            (for [e (search.engine/active-engines)]
              (search.engine/reindex! e opts)))))

(defn reset-tracking!
  "Stop tracking the current indexes. Used when resetting the appdb."
  []
  (when (supports-index?)
    (doseq [e (search.engine/active-engines)]
      (search.engine/reset-tracking! e))))

(defn update!
  "Given a new or updated instance, put all the corresponding search entries if needed in the queue."
  [instance & [always?]]
  (when (supports-index?)
    (when-let [updates (->> (search.spec/search-models-to-update instance always?)
                            (remove (comp search.util/impossible-condition? second))
                            seq)]
      ;; We need to delay execution to handle deletes, which alert us *before* updating the database.
      (search.ingestion/ingest-maybe-async! updates))))

(defn delete!
  "Given a model and a list of model's ids, remove corresponding search entries."
  [model ids]
  (when (supports-index?)
    (doseq [e            (search.engine/active-engines)
            search-model (->> (vals (search.spec/specifications))
                              (filter (comp #{model} :model))
                              (map :name))]
      (search.engine/delete! e search-model ids))))
