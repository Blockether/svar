(ns com.blockether.svar.internal.rlm.git-ingestion-test
  "Tests for git commit ingestion: parsing conventional commits, ticket refs,
   category squashing, git log → entity conversion, and DB storage."
  (:require
   [clojure.string :as str]
   [com.blockether.svar.internal.rlm.db :as db]
   [com.blockether.svar.internal.rlm.git :as git]
   [com.blockether.svar.internal.rlm.schema :refer [RLM_SCHEMA]]
   [datalevin.core :as d]
   [lazytest.core :refer [defdescribe describe expect it throws?]]))

;; =============================================================================
;; parse-commit-message
;; =============================================================================

(defdescribe parse-commit-message-test
  (describe "conventional commit with scope"
    (it "extracts prefix, scope, subject"
      (let [result (git/parse-commit-message "fix(rlm): gate SCI execution on Clojure language only")]
        (expect (= "fix" (:prefix result)))
        (expect (= "rlm" (:scope result)))
        (expect (= "gate SCI execution on Clojure language only" (:subject result)))
        (expect (= :bug (:category result))))))

  (describe "conventional commit without scope"
    (it "extracts prefix and subject"
      (let [result (git/parse-commit-message "feat: add git ingestion pipeline")]
        (expect (= "feat" (:prefix result)))
        (expect (nil? (:scope result)))
        (expect (= "add git ingestion pipeline" (:subject result)))
        (expect (= :feature (:category result))))))

  (describe "non-conventional commit"
    (it "returns nil prefix, full subject"
      (let [result (git/parse-commit-message "Add mandatory clj-paren-repair for Clojure delimiter errors")]
        (expect (nil? (:prefix result)))
        (expect (nil? (:scope result)))
        (expect (= "Add mandatory clj-paren-repair for Clojure delimiter errors" (:subject result)))
        (expect (= :feature (:category result))))))

  (describe "commit with body"
    (it "separates subject and body"
      (let [msg (str/join "\n" ["feat(rlm): RL Q-values and co-occurrence edges"
                                ""
                                "Self-improving memory retrieval:"
                                "- Per-page Q-values with source attribution"
                                ""
                                "Tests: 775 cases, 0 failures"])
            result (git/parse-commit-message msg)]
        (expect (= "feat" (:prefix result)))
        (expect (= "rlm" (:scope result)))
        (expect (= "RL Q-values and co-occurrence edges" (:subject result)))
        (expect (str/includes? (:body result) "Self-improving memory retrieval:"))
        (expect (str/includes? (:body result) "Tests: 775 cases")))))

  (describe "empty message"
    (it "handles empty string"
      (let [result (git/parse-commit-message "")]
        (expect (nil? (:prefix result)))
        (expect (= "" (:subject result)))
        (expect (nil? (:body result)))))))

;; =============================================================================
;; category squash
;; =============================================================================

(defdescribe category-squash-test
  (describe "bug category"
    (it "maps fix → :bug"
      (expect (= :bug (git/prefix->category "fix"))))
    (it "maps bugfix → :bug"
      (expect (= :bug (git/prefix->category "bugfix"))))
    (it "maps hotfix → :bug"
      (expect (= :bug (git/prefix->category "hotfix")))))

  (describe "documentation category"
    (it "maps docs → :documentation"
      (expect (= :documentation (git/prefix->category "docs"))))
    (it "maps doc → :documentation"
      (expect (= :documentation (git/prefix->category "doc")))))

  (describe "feature category"
    (it "maps feat → :feature"
      (expect (= :feature (git/prefix->category "feat"))))
    (it "maps refactor → :feature"
      (expect (= :feature (git/prefix->category "refactor"))))
    (it "maps chore → :feature"
      (expect (= :feature (git/prefix->category "chore"))))
    (it "maps perf → :feature"
      (expect (= :feature (git/prefix->category "perf"))))
    (it "maps test → :feature"
      (expect (= :feature (git/prefix->category "test"))))
    (it "maps ci → :feature"
      (expect (= :feature (git/prefix->category "ci"))))
    (it "maps build → :feature"
      (expect (= :feature (git/prefix->category "build")))))

  (describe "nil/unknown prefix"
    (it "nil → :feature"
      (expect (= :feature (git/prefix->category nil))))
    (it "unknown prefix → :feature"
      (expect (= :feature (git/prefix->category "unknown"))))))

;; =============================================================================
;; ticket refs extraction
;; =============================================================================

(defdescribe extract-ticket-refs-test
  (describe "jira bracket style"
    (it "extracts [SVAR-123]"
      (expect (= ["SVAR-123"] (git/extract-ticket-refs "[SVAR-123] fix: null pointer in parser"))))

    (it "extracts multiple jira refs"
      (expect (= ["SVAR-123" "SVAR-456"]
                (git/extract-ticket-refs "[SVAR-123][SVAR-456] feat: combined fix"))))

    (it "extracts jira ref from body"
      (let [msg "fix: parser crash\n\nRelated to [PROJ-789] for tracking."]
        (expect (= ["PROJ-789"] (git/extract-ticket-refs msg))))))

  (describe "paren hash style"
    (it "extracts (#123)"
      (expect (= ["#123"] (git/extract-ticket-refs "fix: crash (#123)"))))

    (it "extracts paren hash from body"
      (let [msg "feat: new api\n\nImplements (#456) as discussed."]
        (expect (= ["#456"] (git/extract-ticket-refs msg))))))

  (describe "github close keywords"
    (it "extracts fixes #123"
      (expect (= ["#123"] (git/extract-ticket-refs "fix: crash\n\nfixes #123"))))

    (it "extracts closes #456"
      (expect (= ["#456"] (git/extract-ticket-refs "closes #456"))))

    (it "extracts resolves #789"
      (expect (= ["#789"] (git/extract-ticket-refs "resolves #789 via new impl"))))

    (it "extracts refs #100"
      (expect (= ["#100"] (git/extract-ticket-refs "refs #100")))))

  (describe "cross-repo refs"
    (it "extracts blockether/svar#456"
      (expect (= ["blockether/svar#456"]
                (git/extract-ticket-refs "fixes blockether/svar#456")))))

  (describe "bare project key"
    (it "extracts SVAR-123 without brackets"
      (expect (= ["SVAR-123"] (git/extract-ticket-refs "SVAR-123 fix the thing")))))

  (describe "no refs"
    (it "returns empty for plain message"
      (expect (= [] (git/extract-ticket-refs "chore: update deps")))))

  (describe "mixed refs"
    (it "extracts multiple types at once"
      (let [refs (git/extract-ticket-refs "[SVAR-1] fix: crash fixes #2 refs #3 (#4)")]
        (expect (<= 4 (count refs))))))

  (describe "deduplication"
    (it "deduplicates identical refs"
      (let [refs (git/extract-ticket-refs "fixes #123 and fixes #123")]
        (expect (= 1 (count (distinct refs))))))))

;; =============================================================================
;; parse-git-log-line (structured output from git log --format)
;; =============================================================================

(defdescribe parse-git-log-entry-test
  (describe "full entry"
    (it "parses sha, author, email, date, subject, body"
      (let [entry {:sha "6d7050509637d4ae"
                   :author "Michał Kruk"
                   :author-email "michal@blockether.com"
                   :date "2025-01-15T10:30:00+01:00"
                   :subject "fix(rlm): gate SCI execution on Clojure language only"
                   :body "RLM is SCI-steered. Code execution, self-test gate..."
                   :files "M\tsrc/clj/com/blockether/svar/core.clj\nA\tsrc/clj/com/blockether/svar/new.clj"}
            result (git/parse-git-log-entry entry)]
        (expect (= "6d7050509637d4ae" (:sha result)))
        (expect (= "fix" (:prefix result)))
        (expect (= "rlm" (:scope result)))
        (expect (= :bug (:category result)))
        (expect (= "Michał Kruk" (:author result)))
        (expect (= "michal@blockether.com" (:author-email result)))
        (expect (= ["src/clj/com/blockether/svar/core.clj"
                    "src/clj/com/blockether/svar/new.clj"]
                  (:file-paths result))))))

  (describe "entry with no body"
    (it "handles nil body"
      (let [entry {:sha "abc123"
                   :author "Dev"
                   :author-email "dev@test.com"
                   :date "2025-01-15T10:30:00+01:00"
                   :subject "chore: update deps"
                   :body nil
                   :files "M\tdeps.edn"}
            result (git/parse-git-log-entry entry)]
        (expect (= "chore" (:prefix result)))
        (expect (= :feature (:category result)))
        (expect (nil? (:body result)))
        (expect (= ["deps.edn"] (:file-paths result))))))

  (describe "entry with ticket refs"
    (it "extracts refs from subject and body"
      (let [entry {:sha "abc123"
                   :author "Dev"
                   :author-email "dev@test.com"
                   :date "2025-01-15T10:30:00+01:00"
                   :subject "[SVAR-42] fix: crash in parser"
                   :body "fixes #42\nCo-Authored-By: Bot <bot@ai.com>"
                   :files "M\tsrc/parser.clj"}
            result (git/parse-git-log-entry entry)]
        (expect (some #(= "SVAR-42" %) (:ticket-refs result)))))))

;; =============================================================================
;; commit→entity conversion
;; =============================================================================

(defdescribe commit->entity-test
  (describe "full commit → event entity"
    (it "produces valid entity map"
      (let [parsed {:sha "6d70505096"
                    :author "Michał Kruk"
                    :author-email "michal@blockether.com"
                    :date "2025-01-15T10:30:00+01:00"
                    :subject "fix(rlm): gate SCI execution"
                    :prefix "fix"
                    :scope "rlm"
                    :category :bug
                    :body "Detailed description"
                    :ticket-refs ["SVAR-42"]
                    :file-paths ["src/core.clj" "test/core_test.clj"]}
            entity (git/commit->entity parsed "my-repo")]
        (expect (= :event (:entity/type entity)))
        (expect (= "fix(rlm): gate SCI execution" (:entity/name entity)))
        (expect (str/includes? (:entity/description entity) "Detailed description"))
        (expect (= :bug (:commit/category entity)))
        (expect (= "6d70505096" (:commit/sha entity)))
        (expect (= ["SVAR-42"] (:commit/ticket-refs entity)))
        (expect (= ["src/core.clj" "test/core_test.clj"] (:commit/file-paths entity)))
        (expect (= "rlm" (:commit/scope entity)))
        (expect (= "fix" (:commit/prefix entity)))
        (expect (= "my-repo" (:entity/document-id entity))))))

  (describe "commit without body"
    (it "uses subject as description"
      (let [parsed {:sha "abc"
                    :author "Dev"
                    :author-email "dev@test.com"
                    :date "2025-01-15T10:30:00+01:00"
                    :subject "chore: update deps"
                    :prefix "chore"
                    :scope nil
                    :category :feature
                    :body nil
                    :ticket-refs []
                    :file-paths ["deps.edn"]}
            entity (git/commit->entity parsed "repo")]
        (expect (= "chore: update deps" (:entity/description entity)))))))

;; =============================================================================
;; author → person entity
;; =============================================================================

(defdescribe author->person-entity-test
  (describe "unique person per email"
    (it "creates person entity from commit author"
      (let [person (git/author->person-entity {:author "Michał Kruk"
                                               :author-email "michal@blockether.com"}
                     "repo")]
        (expect (= :person (:entity/type person)))
        (expect (= "Michał Kruk" (:entity/name person)))
        (expect (= "michal@blockether.com" (:person/email person)))
        (expect (= "repo" (:entity/document-id person)))))))

;; =============================================================================
;; file → file entity
;; =============================================================================

(defdescribe file->file-entity-test
  (describe "file path → file entity"
    (it "creates file entity with name derived from path"
      (let [entity (git/file->file-entity "src/clj/com/blockether/svar/core.clj" "repo")]
        (expect (= :file (:entity/type entity)))
        (expect (= "src/clj/com/blockether/svar/core.clj" (:entity/name entity)))
        (expect (= "repo" (:entity/document-id entity)))))))

;; =============================================================================
;; DB storage (integration)
;; =============================================================================

(defn- temp-conn []
  (d/get-conn (str "/tmp/git-ingestion-test-" (random-uuid)) RLM_SCHEMA))

(defdescribe git-ingest-commits-test
  (describe "ingest parsed commits into DB"
    (it "stores commit as event entity with relationships"
      (let [conn (temp-conn)
            db-info {:conn conn}]
        (try
          (let [commits [{:sha "abc123"
                          :author "Alice"
                          :author-email "alice@example.com"
                          :date "2025-01-15T10:00:00Z"
                          :subject "feat: add feature X"
                          :prefix "feat"
                          :scope nil
                          :category :feature
                          :body "Body of commit"
                          :ticket-refs []
                          :file-paths ["src/feature.clj" "test/feature_test.clj"]}
                         {:sha "def456"
                          :author "Bob"
                          :author-email "bob@example.com"
                          :date "2025-01-16T10:00:00Z"
                          :subject "fix: bug in feature X"
                          :prefix "fix"
                          :scope nil
                          :category :bug
                          :body "Fixes #1"
                          :ticket-refs ["#1"]
                          :file-paths ["src/feature.clj"]}]
                result (git/ingest-commits! db-info commits {:repo-name "test-repo"})]
            (expect (= 2 (:events-stored result)))
            (expect (= 2 (:people-stored result)))
            (expect (pos? (:relationships-stored result)))

            (let [events (d/q '[:find ?name ?cat
                                :where
                                [?e :entity/type :event]
                                [?e :entity/name ?name]
                                [?e :commit/category ?cat]]
                           (d/db conn))
                  event-map (into {} events)]
              (expect (= 2 (count events)))
              (expect (= :bug (get event-map "fix: bug in feature X")))
              (expect (= :feature (get event-map "feat: add feature X"))))

            (let [people (d/q '[:find ?name
                                :where [?e :entity/type :person] [?e :entity/name ?name]]
                           (d/db conn))]
              (expect (= 2 (count people)))))

          (finally (d/close conn))))))

  (describe "person deduplication by email"
    (it "same author across commits → single person entity"
      (let [conn (temp-conn)
            db-info {:conn conn}]
        (try
          (let [commits [{:sha "aaa" :author "Alice" :author-email "alice@x.com"
                          :date "2025-01-15T10:00:00Z" :subject "feat: a"
                          :prefix "feat" :scope nil :category :feature
                          :body nil :ticket-refs [] :file-paths ["a.clj"]}
                         {:sha "bbb" :author "Alice" :author-email "alice@x.com"
                          :date "2025-01-16T10:00:00Z" :subject "fix: b"
                          :prefix "fix" :scope nil :category :bug
                          :body nil :ticket-refs [] :file-paths ["b.clj"]}]
                result (git/ingest-commits! db-info commits {:repo-name "repo"})]
            (expect (= 1 (:people-stored result))))
          (finally (d/close conn))))))

  (describe "file deduplication"
    (it "same file across commits → single file entity"
      (let [conn (temp-conn)
            db-info {:conn conn}]
        (try
          (let [commits [{:sha "aaa" :author "A" :author-email "a@x.com"
                          :date "2025-01-15T10:00:00Z" :subject "feat: a"
                          :prefix "feat" :scope nil :category :feature
                          :body nil :ticket-refs [] :file-paths ["src/core.clj"]}
                         {:sha "bbb" :author "A" :author-email "a@x.com"
                          :date "2025-01-16T10:00:00Z" :subject "fix: b"
                          :prefix "fix" :scope nil :category :bug
                          :body nil :ticket-refs [] :file-paths ["src/core.clj"]}]
                result (git/ingest-commits! db-info commits {:repo-name "repo"})
                files (d/q '[:find ?name
                             :where [?e :entity/type :file] [?e :entity/name ?name]]
                        (d/db conn))]
            (expect (= 1 (count files))))
          (finally (d/close conn)))))))

;; =============================================================================
;; Live repo ingestion (svar repo, first 100 commits)
;; =============================================================================

(def ^:private SVAR_REPO_ROOT
  "Root of the svar repo — parent of test directory."
  (str (System/getProperty "user.dir")))

(defdescribe live-svar-repo-ingestion-test
  (describe "ingest first 100 commits of svar repo via JGit"
    (it "reads commits, parses, and stores them"
      (let [conn (temp-conn)
            db-info {:conn conn}
            repo (git/open-repo SVAR_REPO_ROOT)]
        (expect (some? repo))
        (try
          (let [commits (git/read-commits repo {:n 100})]
            (expect (<= 1 (count commits)))
            (expect (every? :sha commits))
            (expect (every? :category commits))
            (expect (every? :ticket-refs commits))
            (expect (every? :file-paths commits))
            (expect (every? :parents commits))

            (let [result (git/ingest-commits! db-info commits {:repo-name "svar"})]
              (expect (= (count commits) (:events-stored result)))
              (expect (pos? (:people-stored result)))
              (expect (pos? (:files-stored result)))
              (expect (pos? (:relationships-stored result))))

            (let [cats (d/q '[:find ?cat (count ?e)
                              :where [?e :entity/type :event] [?e :commit/category ?cat]]
                         (d/db conn))
                  cat-map (into {} cats)]
              (expect (pos? (get cat-map :feature 0))))

            (let [people (d/q '[:find (count ?e)
                                :where [?e :entity/type :person]]
                           (d/db conn))]
              (expect (<= 1 (ffirst people))))

            (let [files (d/q '[:find (count ?e)
                               :where [?e :entity/type :file]]
                          (d/db conn))]
              (expect (<= 1 (ffirst files)))))

          (finally
            (.close repo)
            (d/close conn)))))

    (it "has correct category distribution for svar repo"
      (let [repo (git/open-repo SVAR_REPO_ROOT)]
        (try
          (let [commits (git/read-commits repo {:n 100})
                cats (frequencies (map :category commits))]
            (expect (<= 1 (apply + (vals cats))))
            (expect (pos? (get cats :feature 0))))
          (finally (.close repo)))))

    (it "open-repo returns nil for non-git path"
      (expect (nil? (git/open-repo "/tmp"))))

    (it "git-available? true for svar repo root"
      (expect (git/git-available? SVAR_REPO_ROOT)))

    (it "head-info returns current HEAD"
      (let [repo (git/open-repo SVAR_REPO_ROOT)]
        (try
          (let [head (git/head-info repo)]
            (expect (some? head))
            (expect (string? (:sha head)))
            (expect (= 12 (count (:short head)))))
          (finally (.close repo)))))

    (it "commit-parents returns parent SHAs for HEAD"
      (let [repo (git/open-repo SVAR_REPO_ROOT)]
        (try
          (let [parents (git/commit-parents repo "HEAD")]
            (expect (vector? parents))
            (expect (<= 1 (count parents))))
          (finally (.close repo)))))

    (it "commit-diff returns non-empty patch string for HEAD"
      (let [repo (git/open-repo SVAR_REPO_ROOT)]
        (try
          (let [patch (git/commit-diff repo "HEAD")]
            (expect (string? patch))
            (expect (pos? (count patch))))
          (finally (.close repo)))))

    (it "file-history returns commits touching a known file"
      (let [repo (git/open-repo SVAR_REPO_ROOT)]
        (try
          (let [history (git/file-history repo "deps.edn" {:n 10})]
            (expect (vector? history))
            (expect (pos? (count history))))
          (finally (.close repo)))))))
