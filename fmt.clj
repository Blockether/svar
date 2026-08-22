(ns fmt
  "Canonical formatter for svar: zprint, driven by `.zprint.edn` at the repo root.

   `clojure -T:format fix` rewrites every Clojure source under `sources` in place
   (this is what `make format` and `./verify.sh` run); `clojure -T:format check`
   writes nothing and exits non-zero when a file would change.

   The options are loaded through zprint's OWN config loader so the `:option-fn`
   guides in `.zprint.edn` are sci-compiled into real functions — a plain
   `edn/read-string` would leave them as lists zprint rejects. That is the same
   loader the editor tooling uses, so `make format` and format-on-write agree on
   one fixed point instead of fighting over every file."
  (:require [clojure.java.io :as io]
            [zprint.config :as zprint-config]
            [zprint.core :as zprint]))

(def ^:private config-file "The one source of formatting truth, repo-relative." ".zprint.edn")

(def ^:private sources
  "Roots scanned for Clojure sources: files and directories, repo-relative."
  ["src/clj" "test" "build.clj" "fmt.clj"])

(defn- clojure-source?
  "True when `f` is a Clojure/ClojureScript source file."
  [^java.io.File f]
  (and (.isFile f) (boolean (re-find #"\.clj[cs]?$" (.getName f)))))

(defn- source-files
  "Every Clojure source under `sources`, sorted by path."
  []
  (->> sources
       (map io/file)
       (mapcat (fn [^java.io.File f]
                 (if (.isDirectory f) (file-seq f) [f])))
       (filter clojure-source?)
       (sort-by (fn [^java.io.File f]
                  (.getPath f)))))

(defn- options
  "The zprint options map from `config-file`, or a thrown explanation."
  []
  (let [[opts err] (zprint-config/get-config-from-file config-file true)]
    (when err (throw (ex-info (str config-file " is unreadable: " err) {:file config-file})))
    (when-not (map? opts)
      (throw (ex-info (str "No " config-file " at the repo root — run from the project root.")
                      {:file config-file})))
    opts))

(def ^:private max-passes
  "zprint can need one more pass to settle: a form that only just grew multi-line
   reflows once more under the guides. Past this it oscillates, and a formatter
   without a fixed point hands every writer a fresh diff."
  5)

(defn- zprinted
  "`source` through zprint once. A failure is re-thrown naming the file rather
   than silently leaving it alone."
  [opts ^java.io.File f ^String source]
  (try (zprint/zprint-file-str source (.getPath f) opts)
       (catch Exception e
         (throw (ex-info (str "zprint failed on " (.getPath f) ": " (ex-message e))
                         {:file (.getPath f)}
                         e)))))

(defn- reformatted
  "`f` formatted under `opts` to a FIXED POINT, or nil when it already is one.
   zprint is re-applied until it stops changing, so `make format` and the editor's
   format-on-write land on the same bytes instead of trading a diff back and forth."
  [opts ^java.io.File f]
  (let [source (slurp f)]
    (loop [in source
           pass 1]

      (let [out (zprinted opts f in)]
        (cond (= out in) (when (not= out source) out)
              (>= pass max-passes)
              (throw (ex-info (str (.getPath f) " still changes after " max-passes " zprint passes")
                              {:file (.getPath f) :passes max-passes}))
              :else (recur out (inc pass)))))))

(defn- run
  "Format every source file, writing when `write?`. Returns the changed paths."
  [write?]
  (let [opts
        (options)

        files
        (source-files)

        changed
        (into []
              (keep (fn [^java.io.File f]
                      (when-some [out (reformatted opts f)]
                        (when write? (spit f out))
                        (.getPath f))))
              files)]

    (println (format "zprint: %d of %d files %s"
                     (count changed)
                     (count files)
                     (if write? "reformatted" "would change")))
    (doseq [p changed]
      (println " " p))
    changed))

(defn fix "Reformat every Clojure source in place." [_] (run true))

(defn check
  "Report unformatted files and exit 1 when there are any; change nothing."
  [_]
  (when (seq (run false)) (println "Run `make format`.") (System/exit 1)))
