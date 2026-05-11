(ns com.blockether.svar.bench.fence-normalizer-bench
  "Benchmarks reproducing the Vis conversation 0c8188ac hang.

   The original symptom was a virtual thread pegged in
   `java.util.regex.Pattern$BmpCharPropertyGreedy.match` inside
   `normalize-fence-closers`. Live thread dumps showed the LLM iteration
   loop oscillating between `http-post-stream!` (parked on the next SSE
   delta) and `codes/extract-code-blocks` (CPU-bound regex). Each chunk
   re-parsed the entire accumulated buffer with `(?m)…$` patterns over the
   whole string — O(N\u00b2) total work.

   This bench has three scenarios:

     1. `single-final-parse`  \u2014 one extraction over a multi-MB buffer.
                                Measures the linear cost of the new Java
                                `FenceNormalizer` vs whatever it replaced.

     2. `streaming-replay`    \u2014 simulates the original quadratic pattern
                                by calling `extract-code-blocks` on every
                                growing chunk prefix. With the LLM-layer
                                fix (no per-chunk extraction) this is no
                                longer exercised in production, but the
                                bench is here to keep us honest.

     3. `pathological-line`   \u2014 a single multi-MB line studded with stray
                                backticks. The old `(?m)([^\\r\\n`])…\\`{3,}…$`
                                regex hit this hardest because the engine
                                re-anchored end-of-line at every position.

   Run:
     clj -M:bench"
  (:require
   [clojure.string :as str]
   [criterium.core :as crit]
   [com.blockether.svar.internal.codes :as codes])
  (:import
   (com.blockether.svar FenceBlocksParser FenceNormalizer)))

;; -----------------------------------------------------------------------------
;; Fixture builders
;; -----------------------------------------------------------------------------

(defn realistic-line
  "One line of plausibly Clojure-looking content with brackets + a stray
   backtick so the normalizers actually have work to do (rather than
   bailing out on the fast path)."
  [i]
  (format "(defn step-%d [x y] (let [r `(:a :b :c %d)] (str x y r [1 2] {:k :v})))"
    i i))

(defn fenced-response
  "Wrap `n-lines` realistic lines in a single ```clojure``` fence."
  [n-lines]
  (str "```clojure\n"
    (str/join "\n" (map realistic-line (range n-lines)))
    "\n```"))

(defn pathological-buffer
  "A single very-long line built from `n-tokens` short repetitions, ending
   with a glued closer (`)```\u200a`). No internal newlines, so any `(?m)\u2026$`
   regex pays the full line cost on every find() restart."
  [n-tokens]
  (str "(answer (str "
    (str/join " " (repeatedly n-tokens #(rand-nth ["`a`" "`b`" "x" "y" "[1]" "{:k :v}"])))
    "))```"))

;; -----------------------------------------------------------------------------
;; Benchmark drivers
;; -----------------------------------------------------------------------------

(defn- announce [label]
  (println)
  (println (str "=== " label " ==="))
  (flush))

(defn- bench [label thunk]
  (announce label)
  ;; `quick-bench` is enough signal at the per-call scale we care about
  ;; (10\u00b5s\u201310s). Use `bench` for full statistics if needed.
  (crit/quick-bench (thunk)))

(defn single-final-parse-suite []
  (doseq [n [1000 6000 12000]]
    (let [input (fenced-response n)]
      (bench (format "single-final-parse  n-lines=%d  input-size=%.2f MB"
               n (/ (count input) 1024.0 1024.0))
        (fn [] (codes/extract-code-blocks input))))))

(defn fence-normalizer-only-suite
  "Isolate the Java normalizer cost (skip block parsing). This is what
   the LLM streaming layer used to amortize across every SSE chunk."
  []
  (doseq [n [1000 6000 12000]]
    (let [input (fenced-response n)]
      (bench (format "FenceNormalizer/normalize  n-lines=%d  input-size=%.2f MB"
               n (/ (count input) 1024.0 1024.0))
        (fn [] (FenceNormalizer/normalize input))))))

(defn fence-blocks-parser-only-suite
  "Isolate the Java block-parser cost on pre-normalized input. Together
   with `fence-normalizer-only-suite`, these two account for the full
   `extract-code-blocks` budget minus the Clojure wrapper overhead."
  []
  (doseq [n [1000 6000 12000]]
    (let [normalized (FenceNormalizer/normalize (fenced-response n))]
      (bench (format "FenceBlocksParser/parse    n-lines=%d  input-size=%.2f MB"
               n (/ (count normalized) 1024.0 1024.0))
        (fn [] (FenceBlocksParser/parse normalized))))))

(defn streaming-replay-suite
  "Reproduce the original quadratic pattern: parse every prefix of a
   growing stream. With the LLM-layer fix (no per-chunk extract) this
   path is no longer hit; the bench documents what the bug used to feel
   like and prevents regressions if someone reintroduces per-chunk
   extraction."
  []
  (doseq [n [200 800]]
    (let [input  (fenced-response n)
          step   (max 1 (quot (count input) 200))
          ;; Build the prefix list ONCE; the bench just runs the parse.
          prefixes (vec (for [i (range step (count input) step)] (subs input 0 i)))]
      (bench (format "streaming-replay  prefixes=%d  final-size=%.1f KB"
               (count prefixes) (/ (count input) 1024.0))
        (fn []
          (run! codes/extract-code-blocks prefixes))))))

(defn pathological-line-suite
  "Single huge unbroken line. Worst case for the retired `(?m)\u2026$` regex
   passes."
  []
  (doseq [tokens [1000 5000 20000]]
    (let [input (pathological-buffer tokens)]
      (bench (format "pathological-line  tokens=%d  input-size=%.1f KB"
               tokens (/ (count input) 1024.0))
        (fn [] (codes/extract-code-blocks input))))))

;; -----------------------------------------------------------------------------
;; Entrypoint
;; -----------------------------------------------------------------------------

(defn -main [& _]
  (println "svar fence-normalizer benchmarks")
  (println "================================")
  (println "JVM:" (System/getProperty "java.vm.name")
    (System/getProperty "java.runtime.version"))
  (single-final-parse-suite)
  (fence-normalizer-only-suite)
  (fence-blocks-parser-only-suite)
  (streaming-replay-suite)
  (pathological-line-suite)
  (println)
  (println "done.")
  (shutdown-agents))
