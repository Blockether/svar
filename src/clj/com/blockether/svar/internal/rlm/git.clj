(ns com.blockether.svar.internal.rlm.git
  "Git commit ingestion: parse conventional commits, extract ticket refs,
   squash into bug/feature/documentation categories, and store as entities."
  (:require
   [clojure.string :as str]
   [com.blockether.svar.internal.rlm.db :as db]
   [datalevin.core :as d]))

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

(defn parse-git-log-entry
  "Parse a structured git log entry map into enriched commit data.
   Input: {:sha :author :author-email :date :subject :body :files}
   Output: enriched with :prefix :scope :category :ticket-refs :file-paths."
  [{:keys [sha author author-email date subject body files] :as entry}]
  (let [parsed (parse-commit-message (str subject (when body (str "\n\n" body))))
        full-msg (str subject (when body (str "\n\n" body)))
        file-paths (when (seq files)
                     (->> (str/split files #"\n")
                       (mapv #(let [trimmed (str/trim %)]
                                (if-let [tab-idx (str/index-of trimmed "\t")]
                                  (subs trimmed (inc tab-idx))
                                  trimmed)))
                       (remove str/blank?)
                       vec))]
    (merge parsed
      {:sha sha
       :author author
       :author-email author-email
       :date date
       :ticket-refs (extract-ticket-refs full-msg)
       :file-paths (or file-paths [])})))

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
      (:ticket-refs parsed) (assoc :commit/ticket-refs (:ticket-refs parsed))
      (:file-paths parsed)  (assoc :commit/file-paths (:file-paths parsed))
      (:prefix parsed)      (assoc :commit/prefix (:prefix parsed))
      (:scope parsed)       (assoc :commit/scope (:scope parsed)))))

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
        conn (:conn db-info)]

    (let [unique-emails (into {}
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
       :relationships-stored (count relationships)})))

(defn- read-sha->files
  "Parse `git log --format=sha:%H --name-only` into {sha [file-paths]}."
  [git-out]
  (let [lines (str/split git-out #"\n")]
    (loop [lines (seq lines) current-sha nil acc {}]
      (if-let [line (first lines)]
        (let [trimmed (str/trim line)]
          (cond
            (str/blank? trimmed)
            (recur (rest lines) current-sha acc)

            (str/starts-with? trimmed "sha:")
            (let [sha (str/trim (subs trimmed 4))]
              (recur (rest lines) sha acc))

            current-sha
            (recur (rest lines) current-sha
              (update acc current-sha (fnil conj []) trimmed))

            :else
            (recur (rest lines) nil acc)))
        acc))))

(defn read-git-log
  "Read git log from a repository path. Returns parsed commit entries.
   Reads last `n` commits (default 100).
   Two-pass approach: commit metadata + file changes separately."
  ([repo-path]
   (read-git-log repo-path 100))
  ([repo-path n]
   (let [log-result (clojure.java.shell/sh
                      "git" "log" (str "-" n)
                      "--format=sha:%H%nauthor:%an%nemail:%ae%ndate:%aI%nsubject:%s%nbody:%b%n---commit-end---"
                      :dir repo-path)
         files-result (clojure.java.shell/sh
                        "git" "log" (str "-" n)
                        "--format=sha:%H" "--name-only"
                        :dir repo-path)]
     (when (and (= 0 (:exit log-result)) (= 0 (:exit files-result)))
       (let [sha->files (read-sha->files (:out files-result))
             commits (->> (str/split (:out log-result) #"---commit-end---")
                       (map str/trim)
                       (remove str/blank?))]
         (vec
           (for [commit commits
                 :let [lines (str/split commit #"\n")
                       parse-line (fn [prefix line]
                                    (when (str/starts-with? line prefix)
                                      (str/trim (subs line (count prefix)))))
                       sha (some #(parse-line "sha:" %) lines)
                       author (some #(parse-line "author:" %) lines)
                       author-email (some #(parse-line "email:" %) lines)
                       date (some #(parse-line "date:" %) lines)
                       subject (some #(parse-line "subject:" %) lines)
                       body-lines (->> lines
                                    (drop-while #(not (str/starts-with? % "body:")))
                                    rest
                                    (take-while #(not (str/starts-with? % "sha:"))))
                       body (->> body-lines (map str/trim) (remove str/blank?) (str/join "\n"))
                       file-paths (get sha->files sha [])]
                 :when sha]
             (parse-git-log-entry
               {:sha sha
                :author author
                :author-email author-email
                :date date
                :subject subject
                :body (when (seq body) body)
                :files (str/join "\n" file-paths)}))))))))
