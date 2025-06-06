(ns ^:mb/driver-tests metabase.indexed-entities.models.model-index-test
  (:require
   [clojure.set :as set]
   [clojure.test :refer :all]
   [clojurewerkz.quartzite.scheduler :as qs]
   [malli.error :as me]
   [metabase.driver.util :as driver.u]
   [metabase.indexed-entities.models.model-index :as model-index]
   [metabase.indexed-entities.task.index-values :as task.index-values]
   [metabase.query-processor :as qp]
   [metabase.query-processor.compile :as qp.compile]
   [metabase.sync.task.sync-databases :as task.sync-databases]
   [metabase.task.impl :as task]
   [metabase.test :as mt]
   [metabase.test.util :as tu]
   [metabase.util :as u]
   [metabase.util.malli.registry :as mr]
   [toucan2.core :as t2]))

(defmacro ^:private with-scheduler-setup! [& body]
  `(let [scheduler# (#'tu/in-memory-scheduler)]
     ;; need cross thread rebinding from with-redefs not a binding
     (with-redefs [task/scheduler (constantly scheduler#)]
       (qs/standby scheduler#)
       (#'task.index-values/job-init!)
       ;; with-temp creates new dbs which schedules the refresh tasks. without this, if this is the first time the db
       ;; is added you get a gnarly stacktrace about missing keys for sync and refresh. It doesn't ultimately matter
       ;; but let's keep it clean
       (#'task.sync-databases/job-init)
       (qs/start scheduler#)
       (try
         ~@body
         (finally (qs/shutdown scheduler#))))))

(deftest quick-run-through
  (with-scheduler-setup!
    (mt/dataset test-data
      (let [query     (mt/mbql-query products
                        {:filter [:and
                                  [:> $id 0]
                                  [:< $id 10]]})
            pk_ref    (mt/$ids $products.id)
            value_ref (mt/$ids $products.title)]
        (mt/with-model-cleanup [:model/Card]
          ;; with-temp doesn't let us update the model to simulate different values returned
          (let [response    (mt/user-http-request :rasta :post 200 "/card"
                                                  (assoc (mt/card-with-source-metadata-for-query query)
                                                         :type :model
                                                         :name "model index test"
                                                         :visualization_settings {}
                                                         :display "table"))
                model       (t2/select-one :model/Card :id (u/the-id response))
                model-index (mt/user-http-request :rasta :post 200 "/model-index"
                                                  {:model_id  (u/the-id model)
                                                   :pk_ref    pk_ref
                                                   :value_ref value_ref})
                by-key      (fn [k xs]
                              (some (fn [x] (when (= (:key x) k) x)) xs))]
            (testing "We can get the model index"
              (is (=? {:state      "indexed"
                       :model_id   (u/the-id model)
                       :error      nil}
                      (mt/user-http-request :rasta :get 200 (str "/model-index/" (:id model-index))))))
            (testing "We can invoke the task ourself manually"
              (model-index/add-values! model-index)
              (is (= 9 (count (t2/select :model/ModelIndexValue :model_index_id (:id model-index)))))
              (is (= (into #{} cat (mt/rows (qp/process-query
                                             (mt/mbql-query products {:fields [$title]
                                                                      :filter [:and
                                                                               [:> $id 0]
                                                                               [:< $id 10]]}))))
                     (t2/select-fn-set :name :model/ModelIndexValue :model_index_id (:id model-index)))))
            (testing "When the values change the indexed values change"
              ;; update the filter on the model to simulate different values indexed
              (t2/update! :model/Card
                          (u/the-id model)
                          {:dataset_query (mt/mbql-query products
                                            {:filter [:and
                                                      [:> $id 10]
                                                      [:< $id 20]]})})
              (model-index/add-values! model-index)
              (is (= 9 (count (t2/select :model/ModelIndexValue :model_index_id (:id model-index)))))
              (is (= (into #{} cat (mt/rows (qp/process-query
                                             (mt/mbql-query products {:fields [$title]
                                                                      :filter [:and
                                                                               [:> $id 10]
                                                                               [:< $id 20]]}))))
                     (t2/select-fn-set :name :model/ModelIndexValue :model_index_id (:id model-index))))
              (is (=? {:error nil
                       :state "indexed"}
                      (t2/select-one :model/ModelIndex :id (u/the-id model-index)))))
            (let [index-trigger! #(->> (task/scheduler-info)
                                       :jobs
                                       (by-key "metabase.task.IndexValues.job")
                                       :triggers
                                       (by-key (format "metabase.task.IndexValues.trigger.%d"
                                                       (:id model-index))))]
              (testing "There's a task to sync the values"
                (let [trigger (index-trigger!)]
                  (is (some? trigger) "Index trigger not found")
                  (is (= (:schedule model-index) (:schedule trigger)))
                  (is (= {"model-index-id" (:id model-index)}
                         (:data trigger)))))
              (testing "Deleting the model index removes the indexing task"
                (t2/delete! :model/ModelIndex :id (u/the-id model-index))
                (is (nil? (index-trigger!)) "Index trigger not removed")))))))))

(def ^:private empty-changes "empty state map for find changes"
  {:additions #{}, :deletions #{}})

(deftest ^:parallel find-changes-test
  (testing "Identifies no changes"
    (let [values [[1 "apple"] [2 "banana"]]]
      (is (= empty-changes (model-index/find-changes {:current-index values
                                                      :source-values values}))))))

(deftest ^:parallel find-changes-test-2
  (testing "identifies additions"
    (let [values    [[1 "apple"] [2 "banana"]]
          new-value [3 "cherry"]]
      (is (= (update empty-changes :additions conj new-value)
             (model-index/find-changes {:current-index values
                                        :source-values (conj values new-value)}))))))

(deftest ^:parallel find-changes-test-3
  (testing "identifies removals"
    (let [values [[1 "apple"] [2 "banana"]]]
      (is (= (update empty-changes :deletions conj (first values))
             (model-index/find-changes {:current-index values
                                        :source-values (rest values)}))))))

(deftest ^:parallel find-changes-test-4
  (testing "identifies updates"
    (let [values     [[1 "apple"] [2 "banana"]]
          new-values [[1 "applesauce"] [2 "banana"]]]
      (is (= (-> empty-changes
                 (update :deletions conj [1 "apple"])
                 (update :additions conj [1 "applesauce"]))
             (model-index/find-changes {:current-index values
                                        :source-values new-values}))))))

(deftest ^:parallel find-changes-test-5
  (testing "Handles duplicate keys (only one goes into db)"
    (let [values     [[1 "apple"] [2 "banana"]]
          ;; maybe a join involved. Later values win.
          new-values [[1 "apple"] [2 "banana"] [1 "applesauce"]]]
      (is (= (-> empty-changes
                 (update :deletions conj [1 "apple"])
                 (update :additions conj [1 "applesauce"]))
             (model-index/find-changes {:current-index values
                                        :source-values new-values}))))))

(deftest ^:parallel find-changes-test-6
  (testing "When no indexed values are present all are additions"
    (let [values (into [] (map (fn [i] [i (mt/random-name)])) (range 5000))]
      (is (= {:additions (set values)
              :deletions #{}}
             (model-index/find-changes {:current-index []
                                        :source-values (shuffle values)}))))))

(deftest ^:parallel find-changes-test-7
  (testing "All together"
    (let [already-indexed (into [] (map (fn [i] [i (mt/random-name)])) (range 5000))

          ;; two drop away, two ids have new values
          [remove-1 remove-2 change-1 change-2 & unchanged] (shuffle already-indexed)

          updated-1    (update change-1 1 (fn [_previous] (mt/random-name)))
          updated-2    (update change-2 1 (fn [_previous] (mt/random-name)))
          fresh-values (shuffle (conj unchanged updated-1 updated-2))]
      (is (= {:additions #{updated-1 updated-2}
              :deletions #{remove-1 remove-2 change-1 change-2}}
             (model-index/find-changes {:current-index already-indexed
                                        :source-values fresh-values}))))))

(deftest ^:parallel fetch-values-test
  (mt/test-drivers (disj (mt/normal-drivers) :mongo)
    (mt/dataset test-data
      (doseq [[scenario query [field-refs]]
              (remove nil?
                      [[:mbql (mt/mbql-query products {:fields [$id $title]})]
                       [:mbql-custom-column (mt/mbql-query products
                                              {:expressions
                                               {"inc-id"
                                                [:+ $id 1]
                                                "full-name"
                                                [:concat $title "custom"]}})
                        [[[:expression "inc-id"] [:expression "full-name"]]]]
                       [:native (mt/native-query
                                  (qp.compile/compile
                                   (mt/mbql-query products {:fields [$id $title]})))]
                       (when (driver.u/supports? (:engine (mt/db)) :left-join (mt/db))
                         [:join (mt/mbql-query people
                                  {:joins [{:fields       :all,
                                            :source-table $$orders,
                                            :condition    [:=
                                                           $people.id
                                                           &Orders.orders.user_id],
                                            :alias        "Orders"}
                                           {:fields       :all,
                                            :source-table $$products,
                                            :condition    [:=
                                                           &Orders.orders.product_id
                                                           &Products.products.id],
                                            :alias        "Products"}]})
                          [(mt/$ids [&Products.products.id &Products.products.title])]])])]
        (mt/with-temp [:model/Card model (mt/card-with-source-metadata-for-query
                                          query)]
          (testing (str "scenario: " scenario)
            (let [[pk-ref value-ref] (or field-refs
                                         (->> model :result_metadata (map :field_ref)))
                  [error values]  (#'model-index/fetch-values {:model_id  (u/the-id model)
                                                               :pk_ref    pk-ref
                                                               :value_ref value-ref})]
              (is (nil? error))
              (is (> (count values) 0))
              ;; oracle returns BigDecimal ids so need `number?` rather than `int?`
              (is (mr/validate [:sequential [:tuple number? string?]] values)
                  (-> (mr/explain [:sequential [:tuple number? string?]] values)
                      (me/humanize))))))))))

(defn- test-index!
  "Takes a query, pk and value names so it can look up the exact field ref from the metadata. This is what the UI would
  do and ensures that items in the options map are correct."
  [{:keys [query pk-name value-name quantity subset scenario]}]
  (testing scenario
    (mt/with-temp [:model/Card model (assoc (mt/card-with-source-metadata-for-query query)
                                            :type :model
                                            :name "model index test")]
      (let [by-name     (fn [n] (or (some (fn [f]
                                            (when (= (-> f :display_name u/lower-case-en) (u/lower-case-en n))
                                              (:field_ref f)))
                                          (:result_metadata model))
                                    (throw (ex-info (str "Didn't find field: " n)
                                                    {:fields (map :name (:result_metadata model))
                                                     :field  n}))))
            model-index (mt/user-http-request :rasta :post 200 "model-index"
                                              {:model_id  (u/the-id model)
                                               :pk_ref    (by-name pk-name)
                                               :value_ref (by-name value-name)})]
        ;; post most likely creates this, but duplicate to be sure
        (model-index/add-values! model-index)
        (is (= "indexed"
               (t2/select-one-fn :state :model/ModelIndex :id (u/the-id model-index))))
        (is (= quantity
               (t2/count :model/ModelIndexValue :model_index_id (:id model-index))))
        (is (set/subset? subset (t2/select-fn-set :name :model/ModelIndexValue
                                                  :model_index_id (:id model-index))))
        (mt/user-http-request :rasta :delete 200 (str "/model-index/" (:id model-index)))))))

(deftest model-index-test
  (mt/dataset test-data
    (testing "Simple queries"
      (test-index! {:query      (mt/mbql-query products)
                    :pk-name    "id"
                    :value-name "title"
                    :quantity   200
                    :subset     #{"Awesome Concrete Shoes" "Mediocre Wooden Bench"}
                    :scenario   :simple-table}))))

(deftest model-index-test-2
  (mt/dataset test-data
    (testing "With joins"
      (test-index! {:query      (mt/mbql-query people
                                  {:joins [{:fields       :all,
                                            :source-table $$orders,
                                            :condition    [:=
                                                           [:field $people.id nil]
                                                           [:field $orders.user_id {:join-alias "Orders"}]],
                                            :alias        "Orders"}
                                           {:fields       :all,
                                            :source-table $$products,
                                            :condition    [:=
                                                           [:field $orders.product_id {:join-alias "Orders"}]
                                                           [:field $products.id {:join-alias "Products"}]],
                                            :alias        "Products"}]})
                    :pk-name    "Products → ID"
                    :value-name "Products → Title"
                    :quantity   200
                    :subset     #{"Awesome Concrete Shoes" "Mediocre Wooden Bench"}
                    :scenario   :with-joins}))))

(deftest model-index-test-3
  (mt/dataset test-data
    (testing "Native"
      (test-index! {:query      (mt/native-query (qp.compile/compile (mt/mbql-query products)))
                    :pk-name    "id"
                    :value-name "title"
                    :quantity   200
                    :subset     #{"Awesome Concrete Shoes" "Mediocre Wooden Bench"}
                    :scenario   :native}))))

(deftest model-index-test-4
  (mt/dataset test-data
    (testing "Records error message on failure"
      (let [query             (mt/mbql-query products {:fields [$id $title]})
            pk-ref            (mt/$ids $products.id)
            invalid-value-ref (mt/$ids $products.ean)]
        (mt/with-temp [:model/Card model (assoc (mt/card-with-source-metadata-for-query query)
                                                :type :model
                                                :name "model index test")
                       :model/ModelIndex mi {:model_id   (u/the-id model)
                                             :pk_ref     pk-ref
                                             :value_ref  invalid-value-ref
                                             :creator_id (mt/user->id :rasta)
                                             :schedule   "0 0 23 * * ? *"
                                             :state      "initial"}]
          (model-index/add-values! mi)
          (let [bad-attempt (t2/select-one :model/ModelIndex :id (u/the-id mi))]
            (is (=? {:state "error"
                     :error #"(?s)Error executing query.*"}
                    bad-attempt))))))))
