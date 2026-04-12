(ns com.blockether.svar.internal.rlm.batch
  "Parallel fan-out for sub-rlm-query calls.

   sub-rlm-query-batch accepts a vec of call specs (string prompts or opts maps),
   runs them in parallel bounded by the query-env-scoped reentrant semaphore,
   and returns an order-preserving result vec.

   Per-item errors surface as {:error :message :cause} maps — the batch itself
   never throws unless the input is malformed."
  (:require
   [com.blockether.svar.internal.rlm.schema :as schema]
   [taoensso.trove :as trove]))

(defn sub-rlm-query-batch
  "Parallel batch of sub-rlm-query calls.

   `sub-rlm-query-fn` — the bound sub-rlm-query function (from routing).
   `items`            — vec of call specs. Each item is either:
     - a string prompt (→ single-shot sub-rlm-query)
     - a map with :prompt and optional :skills :max-iter :routing :include-trace

   Uses the query-env-scoped reentrant semaphore (*concurrency-semaphore*) for
   HTTP slot acquisition. Native Clojure `future` for binding propagation.
   Timeout clock starts at slot acquisition per item.

   Returns: vec of result maps (same order as input). Errored items have
   {:error <keyword> :message <str>} shape. Batch never throws."
  [sub-rlm-query-fn items]
  (when-not (sequential? items)
    (throw (ex-info "sub-rlm-query-batch expects a vec/seq of items"
             {:type :rlm/invalid-batch-input :got (type items)})))
  (let [sem schema/*concurrency-semaphore*
        cancel-atom (atom false)
        futures
        (mapv
          (fn [item]
            (future
              (try
                (when @cancel-atom
                  {:error :cancelled :message "Batch cancelled"})
                (let [acquire! (when sem (:acquire! sem))
                      release! (when sem (:release! sem))]
                  (when acquire! (acquire!))
                  (try
                    (cond
                      (string? item)
                      (sub-rlm-query-fn item)

                      (map? item)
                      (let [{:keys [prompt]} item
                            opts (dissoc item :prompt)]
                        (when-not prompt
                          (throw (ex-info "Batch item map requires :prompt"
                                   {:type :rlm/missing-prompt :item item})))
                        (sub-rlm-query-fn prompt opts))

                      :else
                      {:error :invalid-item
                       :message (str "Expected string or map, got " (type item))})
                    (finally
                      (when release! (release!)))))
                (catch Exception e
                  {:error   :exception
                   :message (ex-message e)
                   :cause   (ex-data e)}))))
          items)]
    (trove/log! {:level :info :id ::batch-started
                 :data {:item-count (count items)
                        :has-semaphore (some? sem)}
                 :msg "sub-rlm-query-batch started"})
    (let [results (mapv deref futures)]
      (trove/log! {:level :info :id ::batch-done
                   :data {:item-count (count items)
                          :error-count (count (filter :error results))}
                   :msg "sub-rlm-query-batch done"})
      results)))
