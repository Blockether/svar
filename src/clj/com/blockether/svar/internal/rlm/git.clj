(ns com.blockether.svar.internal.rlm.git
  "Git ingestion via JGit (pure JVM, no shell-out).

   Two-layer design:
   * Pure parsers (parse-commit-message, extract-ticket-refs, prefix->category,
     parse-git-log-entry, commit->entity, author->person-entity,
     file->file-entity) — no IO, unit-tested.
   * JGit IO (open-repo, git-available?, read-commits, head-info, blame,
     commit-diff, file-history, commit-parents) — wraps
     org.eclipse.jgit.* with structured return values.

   Consumers: rlm/ingest-git! stores commits as entities; the SCI sandbox
   binds search-commits / commit-history / file-history / blame / commit-diff
   when a git repo is attached to the env."
  (:require
   [clojure.string :as str]
   [datalevin.core :as d])
  (:import
   [java.io ByteArrayOutputStream File]
   [java.time Instant ZoneOffset]
   [org.eclipse.jgit.api Git]
   [org.eclipse.jgit.diff DiffEntry DiffFormatter]
   [org.eclipse.jgit.lib Repository]
   [org.eclipse.jgit.revwalk RevCommit RevWalk]
   [org.eclipse.jgit.storage.file FileRepositoryBuilder]))

;; =============================================================================
;; Pure parsers — no IO, unit-tested directly
;; =============================================================================

(def ^:private CONVENTIONAL_COMMIT_RE
  "Matches conventional commit: type(scope): subject or type: subject"
  #"^(\w+)(?:\(([^)]+)\))?:\s*(.+)$")

(def ^:private CATEGORY_MAP
  "Conventional commit prefix → squashed category (:bug, :feature, :documentation)"
  {"fix"      :bug
   "bugfix"   :bug
   "hotfix"   :bug
   "docs"     :documentation
   "doc"      :documentation
   "feat"     :feature
   "feature"  :feature
   "refactor" :feature
   "chore"    :feature
   "perf"     :feature
   "test"     :feature
   "ci"       :feature
   "build"    :feature})

(defn prefix->category
  "Squash conventional commit prefix into :bug, :feature, or :documentation."
  [prefix]
  (get CATEGORY_MAP prefix :feature))

(defn- parse-subject-line
  "Parse first line of commit message into {:prefix :scope :subject :category}."
  [line]
  (if-let [[_ prefix scope subject] (when (string? line)
                                      (re-matches CONVENTIONAL_COMMIT_RE (str/trim line)))]
    {:prefix  prefix
     :scope   (when (seq scope) scope)
     :subject subject
     :category (prefix->category prefix)}
    {:prefix  nil
     :scope   nil
     :subject (str/trim (or line ""))
     :category :feature}))

(defn parse-commit-message
  "Parse a full commit message string into structured data.
   Returns {:prefix :scope :subject :body :category}."
  [msg]
  (if (str/blank? msg)
    {:prefix nil :scope nil :subject "" :body nil :category :feature}
    (let [[subject-line & body-lines] (str/split msg #"\n" 2)
          parsed (parse-subject-line subject-line)
          body (some-> body-lines first str/trim)]
      (assoc parsed :body (when (seq body) body)))))

(defn extract-ticket-refs
  "Extract all ticket/issue references from a commit message string.
   Supports: [JIRA-123], (#123), fixes #123, closes #123, resolves #123,
   refs #123, owner/repo#123, bare JIRA-123."
  [msg]
  (when (seq msg)
    (let [bracket-jira (re-seq #"\[([A-Z]+-\d+)\]" msg)
          paren-hash (re-seq #"\(#(\d+)\)" msg)
          github-close (re-seq #"(?:fixes|closes|resolves|refs?)\s+#(\d+)" msg)
          cross-repo (re-seq #"(?:fixes|closes|resolves|refs?)\s+([\w-]+/[\w-]+#\d+)" msg)
          bare-jira (re-seq #"(?<!\[)\b([A-Z]{2,}-\d+)\b(?!\])" msg)
          all-refs (concat
                     (map second bracket-jira)
                     (map #(str "#" (second %)) paren-hash)
                     (map #(str "#" (second %)) github-close)
                     (map second cross-repo)
                     (map second bare-jira))]
      (vec (distinct all-refs)))))

(defn- split-shell-files-string
  "Split `git log --name-only` / `--name-status` file list into paths.
   Strips the optional `<status>\\t` prefix (e.g. 'M\\tsrc/x.clj' → 'src/x.clj').
   Preserved for back-compat with parse-git-log-entry's old contract."
  [files-str]
  (->> (str/split files-str #"\n")
    (mapv (fn [line]
            (let [trimmed (str/trim line)]
              (if-let [tab-idx (str/index-of trimmed "\t")]
                (subs trimmed (inc tab-idx))
                trimmed))))
    (remove str/blank?)
    vec))

(defn parse-git-log-entry
  "Enrich a raw commit map with parsed message fields + ticket refs.

   Accepts either shape:
   * Legacy shell shape: `:files` as newline-joined string with `<status>\\t` prefix
   * JGit shape: `:file-paths` as vec of strings (preferred)

   Returns a map with :sha :author :author-email :date :subject :body :prefix
   :scope :category :ticket-refs :file-paths :parents."
  [{:keys [sha author author-email date subject body files file-paths parents]}]
  (let [parsed (parse-commit-message (str subject (when body (str "\n\n" body))))
        full-msg (str subject (when body (str "\n\n" body)))
        paths (cond
                file-paths (vec file-paths)
                (seq files) (split-shell-files-string files)
                :else [])]
    (merge parsed
      {:sha sha
       :author author
       :author-email author-email
       :date date
       :ticket-refs (extract-ticket-refs full-msg)
       :file-paths paths
       :parents (vec parents)})))

(defn commit->entity
  "Convert a parsed commit map into an event entity for DB storage.
   Uses polymorphic attrs: commit/* for git-specific fields.
   `document-id` is the repo name used as document source."
  [parsed document-id]
  (let [description (or (:body parsed) (:subject parsed))]
    (cond-> {:entity/type         :event
             :entity/name         (:subject parsed)
             :entity/description  description
             :entity/document-id  document-id
             :commit/category     (:category parsed)
             :commit/sha          (:sha parsed)
             :commit/date         (:date parsed)}
      (:ticket-refs parsed)  (assoc :commit/ticket-refs (:ticket-refs parsed))
      (:file-paths parsed)   (assoc :commit/file-paths (:file-paths parsed))
      (:prefix parsed)       (assoc :commit/prefix (:prefix parsed))
      (:scope parsed)        (assoc :commit/scope (:scope parsed))
      (seq (:parents parsed)) (assoc :commit/parents (vec (:parents parsed)))
      (:author-email parsed) (assoc :commit/author-email (:author-email parsed)))))

(defn author->person-entity
  "Convert commit author info into a person entity.
   Uses polymorphic attr: person/email for git author email."
  [{:keys [author author-email]} document-id]
  {:entity/type         :person
   :entity/name         author
   :entity/description  (str "Git author: " author " <" author-email ">")
   :entity/document-id  document-id
   :person/email        author-email})

(defn file->file-entity
  "Convert a file path into a file entity."
  [file-path document-id]
  {:entity/type        :file
   :entity/name        file-path
   :entity/description file-path
   :entity/document-id document-id})

(defn ingest-commits!
  "Ingest parsed commits into the RLM DB as entities + relationships.
   Commits → event entities. Authors → person entities. Files → file entities.
   Relationships: person authored event, event contains file.
   Deduplicates people by email, files by path.
   Builds all entities, single batch transact per phase.

   Returns {:events-stored :people-stored :files-stored :relationships-stored}."
  [db-info commits {:keys [repo-name]}]
  (let [document-id (or repo-name "git")
        conn (:conn db-info)
        unique-emails (into {}
                        (comp
                          (map (juxt :author-email identity))
                          (distinct))
                        commits)
        unique-paths (into #{}
                       (comp (mapcat :file-paths) (distinct))
                       commits)

        person-entities (for [[email _] unique-emails
                              :let [commit (get unique-emails email)]]
                          (assoc (author->person-entity commit document-id)
                            :entity/id (java.util.UUID/randomUUID)))
        email->id (zipmap (keys unique-emails)
                    (map :entity/id person-entities))

        file-entities (for [fp unique-paths]
                        (assoc (file->file-entity fp document-id)
                          :entity/id (java.util.UUID/randomUUID)))
        path->id (zipmap unique-paths (map :entity/id file-entities))

        event-entities (for [commit commits]
                         (assoc (commit->entity commit document-id)
                           :entity/id (java.util.UUID/randomUUID)))
        sha->event-id (zipmap (map :sha commits) (map :entity/id event-entities))

        relationships
        (concat
          (for [{:keys [sha author-email]} commits
                :let [event-id (get sha->event-id sha)
                      person-id (get email->id author-email)]
                :when (and event-id person-id)]
            {:relationship/id (java.util.UUID/randomUUID)
             :relationship/type :related-to
             :relationship/source-entity-id person-id
             :relationship/target-entity-id event-id
             :relationship/description "authored"
             :relationship/document-id document-id})
          (for [{:keys [sha file-paths]} commits
                :let [event-id (get sha->event-id sha)]
                fp file-paths
                :let [file-id (get path->id fp)]
                :when (and event-id file-id)]
            {:relationship/id (java.util.UUID/randomUUID)
             :relationship/type :contains
             :relationship/source-entity-id event-id
             :relationship/target-entity-id file-id
             :relationship/description "changed file"
             :relationship/document-id document-id}))]

    (d/transact! conn (vec (concat person-entities file-entities event-entities relationships)))

    {:events-stored (count event-entities)
     :people-stored (count person-entities)
     :files-stored (count file-entities)
     :relationships-stored (count relationships)}))

;; =============================================================================
;; JGit IO — pure-JVM git operations (no shell-out)
;; =============================================================================

(defn open-repo
  "Open a JGit Repository from a filesystem path. Walks up from `path` to
   find `.git/`. Returns the Repository on success or nil if `path` is not
   inside a git working tree (instead of throwing — callers use this as a
   gate for SCI tool binding).

   Callers should close the Repository via (.close repo) when done."
  ^Repository [path]
  (try
    (let [builder (doto (FileRepositoryBuilder.)
                    (.setMustExist true)
                    (.findGitDir (File. (str path)))
                    (.readEnvironment))]
      (.build builder))
    (catch Exception _ nil)))

(defn git-available?
  "True iff `path` resolves to a usable git repository."
  [path]
  (when-let [repo (open-repo path)]
    (try true (finally (.close repo)))))

(defn- rev-commit->instant
  "Convert a RevCommit's commit-time (epoch seconds) to an ISO-8601 string."
  [^RevCommit rc]
  (-> (Instant/ofEpochSecond (.getCommitTime rc))
    (.atOffset ZoneOffset/UTC)
    (.toString)))

(defn- rev-commit-file-paths
  "List of file paths touched by a RevCommit (relative to repo root).
   Handles initial commit (no parents) by listing all files in the tree.
   For merge commits, returns paths changed against the first parent.

   Uses JGit TreeWalk + DiffFormatter for rename-aware diff output."
  [^Repository repo ^RevCommit rc]
  (with-open [rw (RevWalk. repo)
              df (doto (DiffFormatter. (ByteArrayOutputStream.))
                   (.setRepository repo)
                   (.setDetectRenames true))]
    (let [parents (.getParents rc)
          diffs (if (zero? (alength parents))
                  ;; initial commit — diff against empty tree
                  (.scan df nil (.getTree rc))
                  ;; regular commit — diff against first parent
                  (let [parent (.parseCommit rw (.getId ^RevCommit (aget parents 0)))]
                    (.scan df (.getTree parent) (.getTree rc))))]
      (mapv (fn [^DiffEntry de]
              ;; For ADD/MODIFY/RENAME/COPY, new path is real;
              ;; for DELETE, new path is /dev/null so fall back to old path.
              (let [new-path (.getNewPath de)]
                (if (= new-path DiffEntry/DEV_NULL)
                  (.getOldPath de)
                  new-path)))
        diffs))))

(defn- rev-commit->map
  "Convert a JGit RevCommit into the enriched commit map shape consumed by
   commit->entity and ingest-commits!. Includes parents (SHA list) and
   denormalized author-email."
  [^Repository repo ^RevCommit rc]
  (let [ident (.getAuthorIdent rc)
        full-msg (.getFullMessage rc)
        short-msg (.getShortMessage rc)
        body (let [trimmed (str/trim full-msg)
                   [_ & tail] (str/split trimmed #"\n" 2)]
               (some-> tail first str/trim))
        parents (->> (.getParents rc)
                  (map #(.getName ^RevCommit %))
                  vec)
        file-paths (rev-commit-file-paths repo rc)]
    (parse-git-log-entry
      {:sha (.getName rc)
       :author (.getName ident)
       :author-email (.getEmailAddress ident)
       :date (rev-commit->instant rc)
       :subject short-msg
       :body (when (seq body) body)
       :file-paths file-paths
       :parents parents})))

(defn read-commits
  "Read commits from a JGit Repository. Returns vec of enriched commit maps
   (same shape as parse-git-log-entry output).

   Opts:
   * :n         Max commits to return (default 100).
   * :since     ISO-8601 date string. Only commits >= this date.
   * :since-sha Only commits newer than this SHA (exclusive).
   * :path      Restrict to commits touching this path.
   * :author    Restrict to commits by this author email (exact match)."
  [^Repository repo {:keys [n since since-sha path author]
                     :or {n 100}}]
  (with-open [git (Git/wrap repo)
              rw (RevWalk. repo)]
    (let [log-cmd (.log git)
          head-id (.resolve repo "HEAD")]
      (when head-id
        (.add log-cmd head-id)
        (when path
          (.addPath log-cmd path))
        (when since-sha
          (when-let [oid (.resolve repo since-sha)]
            (.not log-cmd oid)))
        (let [since-instant (when since
                              (try (Instant/parse since)
                                (catch Exception _ nil)))
              since-epoch (when since-instant (.getEpochSecond since-instant))
              iter (.iterator (.call log-cmd))]
          (loop [acc (transient [])
                 taken 0]
            (if (or (not (.hasNext iter)) (>= taken n))
              (persistent! acc)
              (let [^RevCommit rc (.next iter)
                    rc-epoch (.getCommitTime rc)
                    before-since? (and since-epoch (< rc-epoch since-epoch))
                    author-match? (or (nil? author)
                                    (= author (.getEmailAddress (.getAuthorIdent rc))))]
                (cond
                  before-since? (persistent! acc)
                  (not author-match?) (recur acc taken)
                  :else (recur (conj! acc (rev-commit->map repo rc))
                          (inc taken)))))))))))

(defn head-info
  "Return {:sha :short :branch} for HEAD, or nil if no HEAD (empty repo)."
  [^Repository repo]
  (when-let [head-id (.resolve repo "HEAD")]
    (let [branch (.getBranch repo)
          sha (.getName head-id)]
      {:sha sha
       :short (subs sha 0 (min 12 (count sha)))
       :branch branch})))

(defn commit-parents
  "Parent SHAs of a commit. Returns vec (empty for root, 1 for normal,
   2+ for merges)."
  [^Repository repo sha]
  (when-let [oid (.resolve repo sha)]
    (with-open [rw (RevWalk. repo)]
      (let [rc (.parseCommit rw oid)]
        (->> (.getParents rc)
          (map #(.getName ^RevCommit %))
          vec)))))

(defn commit-diff
  "Return unified diff patch for a commit as a String. Diffs against the
   first parent (for merges) or against the empty tree (for root commits).

   `sha` may be any git revspec (HEAD, HEAD~1, abc123, main, etc.)."
  [^Repository repo sha]
  (when-let [oid (.resolve repo sha)]
    (with-open [rw (RevWalk. repo)
                out (ByteArrayOutputStream.)
                df (doto (DiffFormatter. out)
                     (.setRepository repo)
                     (.setDetectRenames true))]
      (let [rc (.parseCommit rw oid)
            parents (.getParents rc)
            diffs (if (zero? (alength parents))
                    (.scan df nil (.getTree rc))
                    (let [parent (.parseCommit rw (.getId ^RevCommit (aget parents 0)))]
                      (.scan df (.getTree parent) (.getTree rc))))]
        (doseq [^DiffEntry de diffs]
          (.format df de))
        (.flush df)
        (.toString out "UTF-8")))))

(defn blame
  "Per-line blame attribution for `path`, line range `from..to` (1-indexed,
   inclusive). Returns vec of {:line :sha :short :author :email :date :content}.
   Follows file renames.

   Returns nil if the file is unknown to git."
  [^Repository repo path from to]
  (when-let [_ (.resolve repo "HEAD")]
    (with-open [git (Git/wrap repo)]
      (let [cmd (doto (.blame git)
                  (.setFilePath path)
                  (.setFollowFileRenames true))]
        (try
          (let [result (.call cmd)]
            (when result
              (let [contents (.getResultContents result)
                    line-count (.size contents)
                    ;; Clamp range to file length; from is 1-indexed
                    from-0 (max 0 (dec (long from)))
                    to-0 (min (dec line-count) (dec (long to)))]
                (when (>= to-0 from-0)
                  (vec
                    (for [i (range from-0 (inc to-0))
                          :let [^RevCommit rc (.getSourceCommit result i)
                                ident (when rc (.getAuthorIdent rc))
                                sha (when rc (.getName rc))]]
                      {:line (inc i)
                       :sha sha
                       :short (when sha (subs sha 0 (min 12 (count sha))))
                       :author (when ident (.getName ident))
                       :email (when ident (.getEmailAddress ident))
                       :date (when rc (rev-commit->instant rc))
                       :content (.getString contents i)}))))))
          (catch Exception _ nil))))))

(defn file-history
  "Commits touching `path`, most-recent-first, up to `:n` (default 50).
   Follows renames transparently. Returns vec of enriched commit maps."
  [^Repository repo path & [{:keys [n] :or {n 50}}]]
  (read-commits repo {:n n :path path}))
