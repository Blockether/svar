(ns com.blockether.svar.internal.rlm.skills
  "SKILL.md discovery, parsing, validation, registry, and Datalevin ingestion.

   Skills are loaded from filesystem paths AND ingested into Datalevin as
   :document/type :skill documents. The RLM searches skills the same way it
   searches any other document — via search-documents + fetch-document-content.

   Skill lifecycle:
   1. load-skills — scan files → parse → validate → registry
   2. ingest-skills! — store into Datalevin as :skill documents (searchable)
   3. skill-manage — SCI tool for RLM to create/patch/refine/delete skills
   4. save-skill! — write back to SKILL.md on disk (procedural memory)"
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [datalevin.core :as d]
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

;; =============================================================================
;; Datalevin Ingestion — skills as searchable documents
;; =============================================================================

(defn ingest-skills!
  "Ingests skill registry into Datalevin as :document/type :skill documents.
   Existing skill documents are upserted (matched by :document/id = \"skill-<name>\").
   Skills become searchable via search-documents and fetchable via fetch-document-content.

   Params:
   `db-info`        — map with :conn key (Datalevin connection).
   `skill-registry` — map from load-skills.

   Returns count of ingested skills."
  [{:keys [conn]} skill-registry]
  (when (and conn (seq skill-registry))
    (let [entities (mapv (fn [[skill-name skill]]
                           (let [n (clojure.core/name skill-name)]
                             (cond-> {:document/id        (str "skill-" n)
                                      :document/name      n
                                      :document/type      :skill
                                      :document/title     (or (:description skill) n)
                                      :document/abstract  (or (:abstract skill) "")
                                      :document/extension "md"
                                      :document/created-at (java.util.Date.)
                                      :document/updated-at (java.util.Date.)
                                      :document/certainty-alpha 2.0
                                      :document/certainty-beta  1.0
                                      :skill/body         (:body skill)
                                      :skill/source-path  (or (:source-path skill) "")}
                               (:agent skill)
                               (assoc :skill/agent-config (pr-str (:agent skill)))
                               (:requires skill)
                               (assoc :skill/requires (pr-str (:requires skill)))
                               (:version skill)
                               (assoc :skill/version (str (:version skill))))))
                     skill-registry)]
      (d/transact! conn entities)
      (trove/log! {:level :info :id ::skills-ingested
                   :data {:count (count entities)
                          :names (mapv (comp clojure.core/name first) skill-registry)}
                   :msg "Skills ingested into Datalevin"})
      (count entities))))

;; =============================================================================
;; Skill Management — RLM can create/patch/refine/delete skills
;; =============================================================================

(defn- skill-dir
  "Returns the default project skill directory path for a given skill name.
   Creates .svar/skills/<name>/ if it doesn't exist."
  [skill-name]
  (let [dir (str (fs/path (str (fs/cwd)) ".svar" "skills" (clojure.core/name skill-name)))]
    (fs/create-dirs dir)
    dir))

(defn- build-frontmatter
  "Builds YAML frontmatter string from a skill-manage opts map."
  [{:keys [name description abstract agent requires version]}]
  (str "---\n"
    "name: " (clojure.core/name name) "\n"
    "description: " (or description "") "\n"
    (when abstract (str "abstract: " abstract "\n"))
    (when version (str "version: " version "\n"))
    (when agent
      (str "agent:\n"
        (when (:tools agent) (str "  tools: " (pr-str (:tools agent)) "\n"))
        (when (:max-iter agent) (str "  max-iter: " (:max-iter agent) "\n"))
        (when (:timeout-ms agent) (str "  timeout-ms: " (:timeout-ms agent) "\n"))))
    (when requires
      (str "requires:\n"
        (when (some? (:docs requires)) (str "  docs: " (:docs requires) "\n"))
        (when (some? (:git requires)) (str "  git: " (:git requires) "\n"))
        (when (seq (:env requires)) (str "  env: " (pr-str (:env requires)) "\n"))))
    "---\n"))

(defn save-skill!
  "Writes a skill to disk as SKILL.md. Creates .svar/skills/<name>/SKILL.md.
   Used by skill-manage :create and :refine to persist RLM-generated skills."
  [skill-def]
  (let [dir (skill-dir (:name skill-def))
        path (str (fs/path dir "SKILL.md"))
        content (str (build-frontmatter skill-def) "\n" (:body skill-def))]
    (spit path content)
    (trove/log! {:level :info :id ::skill-saved
                 :data {:name (:name skill-def) :path path}
                 :msg "Skill saved to disk"})
    path))

(defn skill-manage
  "SCI-callable skill management tool. RLM uses this to create, patch, refine,
   or delete skills. Changes are persisted to disk AND Datalevin.

   Actions:
   :create  — create a new skill
             {:name :kw :description \"...\" :body \"...\" :abstract \"...\"
              :agent {:tools [...] :max-iter N} :requires {...}}
   :patch   — targeted body update (string replacement)
             {:name :kw :old \"old text\" :new \"new text\"}
   :refine  — update the abstract/description without changing body
             {:name :kw :abstract \"new abstract\" :description \"new desc\"}
   :delete  — remove a skill from disk and Datalevin
             {:name :kw}

   Params:
   `db-info-atom`    — atom with Datalevin db-info.
   `skill-registry`  — atom with current skill registry map.
   `action`          — keyword (:create :patch :refine :delete).
   `opts`            — action-specific opts map."
  [db-info-atom skill-registry action opts]
  (case action
    :create
    (let [{:keys [name body]} opts
          _ (when-not name (throw (ex-info "skill-manage :create requires :name" {:type :rlm/skill-manage-error})))
          _ (when-not body (throw (ex-info "skill-manage :create requires :body" {:type :rlm/skill-manage-error})))
          skill-def (-> opts
                      (assoc :name (keyword name))
                      (enrich-skill))
          path (save-skill! skill-def)]
      ;; Update registry
      (swap! skill-registry assoc (:name skill-def) (assoc skill-def :source-path path))
      ;; Ingest into Datalevin
      (when-let [db @db-info-atom]
        (ingest-skills! db {(:name skill-def) skill-def}))
      {:created (:name skill-def) :path path})

    :patch
    (let [{:keys [name old new]} opts
          skill-name (keyword name)
          skill (get @skill-registry skill-name)]
      (when-not skill (throw (ex-info (str "Unknown skill: " skill-name) {:type :rlm/unknown-skill})))
      (let [updated-body (str/replace (:body skill) old new)
            updated-skill (assoc skill :body updated-body)
            path (save-skill! updated-skill)]
        (swap! skill-registry assoc skill-name (assoc updated-skill :source-path path))
        (when-let [db @db-info-atom]
          (ingest-skills! db {skill-name updated-skill}))
        {:patched skill-name :path path}))

    :refine
    (let [{:keys [name abstract description]} opts
          skill-name (keyword name)
          skill (get @skill-registry skill-name)]
      (when-not skill (throw (ex-info (str "Unknown skill: " skill-name) {:type :rlm/unknown-skill})))
      (let [updated (cond-> skill
                      abstract (assoc :abstract abstract)
                      description (assoc :description description))
            path (save-skill! updated)]
        (swap! skill-registry assoc skill-name (assoc updated :source-path path))
        (when-let [db @db-info-atom]
          (ingest-skills! db {skill-name updated}))
        {:refined skill-name :path path}))

    :delete
    (let [skill-name (keyword (:name opts))
          skill (get @skill-registry skill-name)]
      (when skill
        ;; Remove from disk
        (when-let [sp (:source-path skill)]
          (when (fs/exists? sp)
            (fs/delete sp)
            (let [parent (fs/parent sp)]
              (when (and (fs/exists? parent) (empty? (fs/list-dir parent)))
                (fs/delete parent)))))
        ;; Remove from Datalevin
        (when-let [{:keys [conn]} @db-info-atom]
          (when conn
            (when-let [eid (d/entid (d/db conn) [:document/id (str "skill-" (clojure.core/name skill-name))])]
              (d/transact! conn [[:db/retractEntity eid]]))))
        ;; Remove from registry
        (swap! skill-registry dissoc skill-name)
        (trove/log! {:level :info :id ::skill-deleted
                     :data {:name skill-name}
                     :msg "Skill deleted"})
        {:deleted skill-name}))

    (throw (ex-info (str "Unknown skill-manage action: " action)
             {:type :rlm/skill-manage-error :action action}))))
