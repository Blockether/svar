(ns com.blockether.svar.internal.rlm.skills
  "SKILL.md discovery, parsing, validation, and registry construction.

   Loads skills from project + global paths (see PROJECT_SUBPATHS / GLOBAL_PATHS)
   and returns a registry map of {skill-name → skill-def}. The registry is
   injected into the RLM env at query-env! init time and consumed by both
   build-system-prompt (for the main RLM manifest) and sub-rlm-query (for
   per-call skill body injection)."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [taoensso.trove :as trove]
   [yamlstar.core :as yaml]))

(def PROJECT_SUBPATHS
  "Project-local SKILL.md discovery subdirectories, searched under the git root
   walked up from cwd. Project entries win on name collision."
  [".svar/skills"
   ".claude/skills"
   ".opencode/skills"
   ".agents/skills"
   "skills"])

(def GLOBAL_SUBPATHS
  "Global SKILL.md discovery subdirectories, searched under the user home dir.
   Project entries override these on name collision."
  [".svar/skills"
   ".claude/skills"
   ".config/opencode/skills"
   ".agents/skills"])

(def NAME_RE
  "Skill name validation regex. Lowercase letters/digits with optional hyphens,
   1-64 chars, must start with a letter or digit. Matches Claude/OpenCode rules."
  #"^[a-z0-9][a-z0-9-]{0,63}$")

(def MAX_DESCRIPTION_CHARS 1024)
(def MAX_ABSTRACT_CHARS 200)

(defn- home []
  (System/getProperty "user.home"))

(defn- find-project-root
  "Walks from start-dir upward looking for .git. Falls back to start-dir when
   no git root is found (e.g. running outside a repo)."
  [start-dir]
  (let [abs (fs/absolutize start-dir)]
    (loop [dir abs]
      (cond
        (nil? dir) (str abs)
        (fs/exists? (fs/path dir ".git")) (str dir)
        :else (recur (fs/parent dir))))))

(defn- scan-dir-for-skill-md
  "Lists SKILL.md files one level deep under dir (each in its own subdir).
   Returns a vec of absolute paths as strings. Missing/unreadable dir → []."
  [dir]
  (if-not (and dir (fs/exists? dir) (fs/directory? dir))
    []
    (try
      (vec
        (for [subdir (fs/list-dir dir)
              :when (fs/directory? subdir)
              :let [skill-md (fs/path subdir "SKILL.md")]
              :when (fs/exists? skill-md)]
          (str skill-md)))
      (catch Exception e
        (trove/log! {:level :warn :id ::scan-failed
                     :data {:dir (str dir) :error (ex-message e)}
                     :msg "Failed to scan skill directory"})
        []))))

(defn- split-frontmatter
  "Splits SKILL.md content into [frontmatter-yaml-str body-md-str].
   Expects content to start with `---\\n`, followed by YAML, followed by `\\n---\\n`.
   Returns nil if no valid frontmatter."
  [content]
  (when (string? content)
    (let [trimmed (if (str/starts-with? content "\uFEFF") (subs content 1) content)]
      (when (str/starts-with? trimmed "---\n")
        (let [after-open (subs trimmed 4)
              close-idx (str/index-of after-open "\n---\n")]
          (when close-idx
            [(subs after-open 0 close-idx)
             (subs after-open (+ close-idx 5))]))))))

(defn- normalize-compatibility
  "Accepts scalar string, keyword, sequence, or map. Returns a set of lowercase
   strings. Handles all the ways YAML can represent `compatibility: [svar]`."
  [v]
  (cond
    (nil? v) #{}
    (string? v) #{(str/lower-case v)}
    (keyword? v) #{(str/lower-case (name v))}
    (map? v) (into #{} (map (fn [k] (str/lower-case (name k)))) (keys v))
    (sequential? v) (into #{}
                      (map (fn [x]
                             (cond
                               (string? x) (str/lower-case x)
                               (keyword? x) (str/lower-case (name x))
                               :else (str/lower-case (str x)))))
                      v)
    :else #{}))

(defn- coerce-name
  "Coerces a YAML name value to a Clojure keyword, or nil if invalid."
  [v]
  (cond
    (keyword? v) v
    (string? v) (keyword v)
    :else nil))

(defn- parse-skill-file
  "Reads a SKILL.md at path and returns a partially-populated skill-def or nil
   on parse failure. yamlstar returns string-keyed maps, so we keywordize keys
   via clojure.walk so downstream code can use keyword access. Validation
   happens later in validate-skill."
  [path]
  (try
    (let [content (slurp path)
          split (split-frontmatter content)]
      (when split
        (let [[fm-yaml body] split
              raw (yaml/load fm-yaml)
              fm (walk/keywordize-keys raw)
              dir-name (str (fs/file-name (fs/parent path)))]
          {:name              (coerce-name (:name fm))
           :description       (:description fm)
           :abstract (:abstract fm)
           :body              (str/trim body)
           :compatibility     (normalize-compatibility (:compatibility fm))
           :agent             (:agent fm)
           :requires          (:requires fm)
           :version           (:version fm)
           :license           (:license fm)
           :source-path       path
           :dir-name          dir-name})))
    (catch Exception e
      (trove/log! {:level :warn :id ::parse-failed
                   :data {:path path :error (ex-message e)}
                   :msg "Failed to parse SKILL.md"})
      nil)))

(defn- validate-skill
  "Returns [ok? reason]. Minimal validation — name format, compatibility gate,
   description length, body presence, dir-name match."
  [{:keys [name description compatibility body dir-name]}]
  (cond
    (nil? name)
    [false :name-missing]

    (not (re-matches NAME_RE (clojure.core/name name)))
    [false :name-format-invalid]

    (not= (clojure.core/name name) (str/lower-case dir-name))
    [false :name-dir-mismatch]

    (or (not (string? description)) (str/blank? description))
    [false :description-missing]

    (> (count description) MAX_DESCRIPTION_CHARS)
    [false :description-too-long]

    (and (seq compatibility) (not (contains? compatibility "svar")))
    [false :compatibility-not-svar]

    (or (not (string? body)) (str/blank? body))
    [false :body-missing]

    :else [true nil]))

(def ^:private DEFAULT_AGENT
  "Default agent config for skills without an explicit :agent block.
   Inherits all parent tools (empty allowlist = no restriction)."
  {:tools      []
   :reasoning  nil
   :max-iter   5
   :timeout-ms 30000})

(def ^:private DEFAULT_REQUIRES
  "Default requires for skills without an explicit :requires block."
  {:docs false
   :git  false
   :env  []})

(defn- derive-abstract
  "Returns :abstract if present in frontmatter, else auto-truncates :description
   to MAX_ABSTRACT_CHARS. Used in the main RLM skill manifest."
  [{:keys [description abstract]}]
  (or (when (string? abstract) (str/trim abstract))
    (when (string? description)
      (let [trimmed (str/trim description)]
        (if (> (count trimmed) MAX_ABSTRACT_CHARS)
          (str (subs trimmed 0 (- MAX_ABSTRACT_CHARS 3)) "...")
          trimmed)))))

(defn- enrich-skill
  "Fills in SVAR-specific defaults for skills that lack them (compatibility layer
   for existing Claude/OpenCode skills). Merges defaults under :agent and :requires
   only when the skill doesn't already provide them."
  [skill]
  (-> skill
    (update :agent #(merge DEFAULT_AGENT %))
    (update :requires #(merge DEFAULT_REQUIRES %))
    (assoc :abstract (derive-abstract skill))))

(defn- discovery-paths
  "Returns the ordered vec of absolute directory paths to scan for SKILL.md
   files. Project-local paths come first (win on name collision)."
  [{:keys [project-root roots]}]
  (let [proj-root (or project-root (str (fs/cwd)))
        git-root (find-project-root proj-root)
        project-dirs (mapv #(str (fs/path git-root %)) PROJECT_SUBPATHS)
        extra-dirs  (mapv #(str (fs/path git-root %)) (or roots []))
        global-dirs (mapv #(str (fs/path (home) %)) GLOBAL_SUBPATHS)]
    (vec (concat project-dirs extra-dirs global-dirs))))

(defn load-skills
  "Scans all discovery paths, parses SKILL.md files, validates, dedupes by name.
   First path to define a given :name wins (project > global).

   Opts:
   - :project-root — String. Defaults to current working directory.
   - :roots        — Vec of extra project-relative subpaths to scan.
   - :allow        — Set/vec of skill name keywords. Whitelist. Nil = allow all.
   - :deny         — Set/vec of skill name keywords. Blacklist.

   Returns: {skill-name-keyword → skill-def-map}
   Skill-def includes :name :description :abstract :body :compatibility
   :agent :requires :source-path :dir-name :version :license."
  ([] (load-skills {}))
  ([{:keys [allow deny] :as opts}]
   (let [paths (discovery-paths opts)
         skill-files (mapcat scan-dir-for-skill-md paths)
         parsed (keep parse-skill-file skill-files)
         validated (keep (fn [s]
                           (let [[ok? reason] (validate-skill s)]
                             (if ok?
                               (enrich-skill s)
                               (do (trove/log! {:level :warn :id ::skill-rejected
                                                :data {:path (:source-path s)
                                                       :name (:name s)
                                                       :reason reason}
                                                :msg "Skill rejected at load time"})
                                   nil))))
                     parsed)
         allow-set (when (seq allow) (set allow))
         deny-set  (when (seq deny)  (set deny))
         filtered (->> validated
                    (remove (fn [s] (and deny-set (contains? deny-set (:name s)))))
                    (filter (fn [s] (or (nil? allow-set) (contains? allow-set (:name s))))))
         ;; Dedupe by :name — first wins (project precedence)
         registry (reduce
                    (fn [m s]
                      (if (contains? m (:name s))
                        (do (trove/log! {:level :info :id ::skill-collision
                                         :data {:name (:name s)
                                                :kept-path (get-in m [(:name s) :source-path])
                                                :discarded-path (:source-path s)}
                                         :msg "Skill name collision — earlier path wins"})
                            m)
                        (assoc m (:name s) s)))
                    {}
                    filtered)]
     (trove/log! {:level :info :id ::skills-loaded
                  :data {:count (count registry)
                         :names (vec (keys registry))}
                  :msg "Skills loaded"})
     registry)))

(defn skills-manifest-block
  "Builds the compact SKILLS: block for the main RLM system prompt.
   Returns empty string when the registry is empty.

   Format:
     SKILLS (pass :skills [...] to sub-rlm-query, max 2 per call):
       :name — <abstract>
       ..."
  [skill-registry]
  (if (empty? skill-registry)
    ""
    (let [entries (sort-by key skill-registry)
          lines (mapv (fn [[k skill]]
                        (str "  :" (clojure.core/name k) " — " (:abstract skill)))
                  entries)]
      (str "\nSKILLS (pass :skills [...] to sub-rlm-query, max 2 per call):\n"
        (str/join "\n" lines) "\n"))))
