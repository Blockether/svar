(ns com.blockether.svar.internal.rlm.trace
  "RLM execution trace formatting utilities."
  (:require
   [clojure.string :as str]))

(defn format-trace
  "Formats an RLM execution trace into a string. Internal helper."
  [trace {:keys [max-response-length max-code-length max-result-length show-stdout?]
          :or {max-response-length 500 max-code-length 300 max-result-length 200
               show-stdout? true}}]
  (let [truncate (fn [s n] (if (and s (> (count s) n)) (str (subs s 0 n) "...") s))
        format-execution (fn [{:keys [id code result stdout error execution-time-ms]}]
                           (str "║ [" id "] "
                             (truncate (str/replace (or code "") #"\n" " ") max-code-length) "\n"
                             "║     "
                             (if error
                               (str "ERROR: " error)
                               (str "=> " (truncate (pr-str result) max-result-length)))
                             (when execution-time-ms (str " (" execution-time-ms "ms)"))
                             (when (and show-stdout? stdout (not (str/blank? stdout)))
                               (str "\n║     STDOUT: " (truncate stdout max-result-length)))))
        format-iteration (fn [{:keys [iteration response executions final?]}]
                           (str "\n"
                             "╔══════════════════════════════════════════════════════════════════════════════\n"
                             "║ ITERATION " iteration (when final? " [FINAL]") "\n"
                             "╠══════════════════════════════════════════════════════════════════════════════\n"
                             "║ RESPONSE:\n"
                             "║ " (str/replace (truncate response max-response-length) #"\n" "\n║ ") "\n"
                             (when (seq executions)
                               (str "╠──────────────────────────────────────────────────────────────────────────────\n"
                                 "║ EXECUTIONS (" (count executions) "):\n"
                                 (str/join "\n"
                                   (map format-execution executions))
                                 "\n"))
                             "╚══════════════════════════════════════════════════════════════════════════════"))]
    (if (empty? trace)
      "No trace entries."
      (str "RLM EXECUTION TRACE (" (count trace) " iterations)\n"
        (str/join "\n" (map format-iteration trace))))))

(defn pprint-trace
  "Pretty-prints an RLM execution trace to stdout for debugging.

   Prints the formatted trace to *out* and returns the formatted string.

   Params:
   `trace` - Vector of trace entries from query-env! result.
   `opts` - Map, optional:
     - :max-response-length - Truncate LLM response (default: 500).
     - :max-code-length - Truncate code blocks (default: 300).
     - :max-result-length - Truncate execution results (default: 200).
     - :show-stdout? - Show stdout output (default: true).

   Returns:
   String with formatted trace output (also printed to stdout)."
  ([trace] (pprint-trace trace {}))
  ([trace opts]
   (let [s (format-trace trace opts)]
     (println s)
     s)))

(defn print-trace
  "Prints an RLM execution trace to stdout. Alias for pprint-trace."
  ([trace] (pprint-trace trace))
  ([trace opts] (pprint-trace trace opts)))
