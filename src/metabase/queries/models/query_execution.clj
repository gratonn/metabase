(ns metabase.queries.models.query-execution
  "QueryExecution is a log of very time a query is executed, and other information such as the User who executed it, run
  time, context it was executed in, etc."
  (:require
   [malli.error :as me]
   [metabase.analytics.core :as analytics]
   [metabase.lib.schema.info :as lib.schema.info]
   [metabase.models.interface :as mi]
   [metabase.util :as u]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.malli.registry :as mr]
   [methodical.core :as methodical]
   [toucan2.core :as t2]
   [toucan2.tools.disallow :as t2.disallow]))

(methodical/defmethod t2/table-name :model/QueryExecution [_model] :query_execution)

(derive :model/QueryExecution :metabase/model)
(derive :model/QueryExecution ::t2.disallow/update)

(t2/deftransforms :model/QueryExecution
  {:json_query mi/transform-json
   :status     mi/transform-keyword
   :context    mi/transform-keyword})

(defn- validate-context [context]
  (when-let [error (me/humanize (mr/explain ::lib.schema.info/context context))]
    (throw (ex-info (tru "Invalid query execution context: {0}" (pr-str error))
                    {:error error}))))

(t2/define-before-insert :model/QueryExecution
  [{context :context, :as query-execution}]
  (u/prog1 (analytics/include-sdk-info query-execution)
    (validate-context context)))

(t2/define-after-select :model/QueryExecution
  [{:keys [result_rows] :as query-execution}]
  ;; sadly we have 2 ways to reference the row count :(
  (assoc query-execution :row_count (or result_rows 0)))
