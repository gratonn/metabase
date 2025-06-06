(ns metabase.lib.metadata.cached-provider-test
  (:require
   #?@(:cljs ([metabase.test-runner.assert-exprs.approximately-equal]))
   [clojure.test :refer [deftest is testing]]
   [metabase.lib.metadata.cached-provider :as lib.metadata.cached-provider]
   [metabase.lib.metadata.protocols :as lib.metadata.protocols]
   [metabase.lib.test-metadata :as meta]))

#?(:cljs (comment metabase.test-runner.assert-exprs.approximately-equal/keep-me))

(deftest ^:parallel caching-test
  (let [fetch-count (atom 0)
        missing-id  123
        provider    (lib.metadata.cached-provider/cached-metadata-provider
                     #_{:clj-kondo/ignore [:missing-protocol-method]}
                     (reify
                       lib.metadata.protocols/MetadataProvider
                       (metadatas [_this metadata-type ids]
                         (case metadata-type
                           :metadata/table
                           (->> (for [id ids]
                                  (do
                                    (swap! fetch-count inc)
                                    (when (not= id missing-id)
                                      (assoc (meta/table-metadata :venues) :id id))))
                                (filter some?))))))]
    (testing "Initial fetch"
      (is (=? {:id 1}
              (lib.metadata.protocols/table provider 1)))
      (is (= 1
             @fetch-count)))
    (testing "Second fetch"
      (is (=? {:id 1}
              (lib.metadata.protocols/table provider 1)))
      (is (= 1
             @fetch-count)))
    (testing "Third fetch"
      (is (=? {:id 1}
              (lib.metadata.protocols/table provider 1)))
      (is (= 1
             @fetch-count)))
    (testing "Bulk fetch"
      (is (=? [{:id 1}]
              (lib.metadata.protocols/metadatas provider :metadata/table #{1})))
      (is (= 1
             @fetch-count))
      (testing "Fetch a new Table, 1 Table already fetched"
        (is (=? [{:id 1}
                 {:id 2}]
                (lib.metadata.protocols/metadatas provider :metadata/table #{1 2})))
        (is (= 2
               @fetch-count)))
      (testing "Bulk fetch again, should use cached results"
        (is (=? [{:id 1}
                 {:id 2}]
                (lib.metadata.protocols/metadatas provider :metadata/table #{1 2})))
        (is (= 2
               @fetch-count)))
      (testing "Fetch a missing id, first fetch should inc fetch count"
        (let [results (lib.metadata.protocols/metadatas provider :metadata/table #{1 missing-id})]
          (is (=? [{:id 1}]
                  results)))
        (is (= 3
               @fetch-count)))
      (testing "Fetch a missing id, second fetch should not inc fetch count"
        (let [results (lib.metadata.protocols/metadatas provider :metadata/table #{1 missing-id})]
          (is (=? [{:id 1}]
                  results)))
        (is (= 3
               @fetch-count))))))

(deftest ^:parallel equality-test
  (is (= (lib.metadata.cached-provider/cached-metadata-provider nil)
         (lib.metadata.cached-provider/cached-metadata-provider nil))))
