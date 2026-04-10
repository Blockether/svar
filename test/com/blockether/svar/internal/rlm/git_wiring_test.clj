(ns com.blockether.svar.internal.rlm.git-wiring-test
  "Integration tests for W2 — git.clj wired into the RLM runtime.
   Covers (1) single-repo wiring, (2) multi-repo auto-dispatch via path for
   blame/file-history and via SHA for commit-diff/commit-parents, (3) system
   prompt renders one GIT REPO block per attached repo, (4) dispose-env!
   closes every attached repo.

   All git tools are prefixed `git-`.

   NOTE on temp repo creation: we use shell `git` (not JGit's Git.init) so
   we can pass `-c gpg.format=openpgp -c commit.gpgsign=false`, bypassing
   any user-level `gpg.format=ssh` setting that JGit 6.10 can't parse.
   Production code (rlm-git/open-repo + read-commits) only reads repo
   state and never touches gpg config, so it works fine on the resulting
   .git/ directory."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [lazytest.core :refer [defdescribe describe expect it]]
   [sci.core :as sci]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.rlm :as sut]
   [com.blockether.svar.internal.rlm.core :as rlm-core]
   [com.blockether.svar.internal.rlm.git :as rlm-git])
  (:import
   [java.io File]
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

(def ^:private SVAR_REPO_ROOT
  (str (System/getProperty "user.dir")))

(defn- stub-router []
  (llm/make-router [{:id :test :api-key "test" :base-url "http://localhost"
                     :models [{:name "stub" :input-cost 0 :output-cost 0}]}]))

(defn- resolve-in-sandbox
  [env sym]
  (:val (sci/eval-string+ (:sci-ctx env)
          (str "(resolve '" sym ")")
          {:ns (:sandbox-ns env)})))

(defn- eval-in-sandbox
  [env code]
  (:val (sci/eval-string+ (:sci-ctx env) code {:ns (:sandbox-ns env)})))

(defn- make-temp-git-repo!
  "Initialize a throwaway git repo in a fresh temp directory with one file
   and one commit via shell `git` (so we can pin gpg.format=openpgp and
   bypass the user's possible gpg.format=ssh setting which JGit 6.10 can't
   parse). Returns {:path abs-path :file-abs abs-file-path :sha commit-sha}."
  []
  (let [dir (.toFile (Files/createTempDirectory "svar-gittest-"
                       (make-array FileAttribute 0)))
        dir-path (.getAbsolutePath dir)
        file (File. dir "README.md")
        ;; -c flags override any conflicting global config for THIS invocation
        ;; only. Critical when the user has gpg.format=ssh set globally.
        git-c ["-c" "commit.gpgsign=false"
               "-c" "gpg.format=openpgp"
               "-c" "user.email=test@example.com"
               "-c" "user.name=Test Bot"
               "-c" "init.defaultBranch=main"]
        git-run (fn [& args]
                  (let [result (apply shell/sh (concat ["git"] git-c args [:dir dir-path]))]
                    (when-not (zero? (:exit result))
                      (throw (ex-info (str "git " (str/join " " args) " failed: "
                                        (:err result))
                               {:dir dir-path :args args :exit (:exit result)})))
                    result))]
    (spit file "hello from the temp repo\nsecond line\nthird line\n")
    (git-run "init" "-q")
    (git-run "add" "README.md")
    (git-run "commit" "-q" "-m" "feat: initial seed commit")
    (let [sha (str/trim (:out (git-run "rev-parse" "HEAD")))]
      {:path dir-path
       :file-abs (.getAbsolutePath file)
       :sha sha})))

(defn- rm-rf! [^String path]
  (let [f (io/file path)]
    (when (.isDirectory f)
      (doseq [c (reverse (file-seq f))]
        (try (.delete c) (catch Exception _ nil))))))

(defdescribe ingest-git-test
  (describe "sut/ingest-git!"
    (it "ingests commits + attaches repo + returns stats"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (let [result (sut/ingest-git! env
                         {:repo-path SVAR_REPO_ROOT
                          :repo-name "svar"
                          :n 30})]
            (expect (= "svar" (:repo-name result)))
            (expect (pos? (:events-stored result)))
            (expect (pos? (:people-stored result)))
            (expect (some? (:head result)))
            (expect (contains? @(:git-repos-atom env) "svar"))
            (expect (contains? @(:git-contexts-atom env) "svar")))
          (finally (sut/dispose-env! env)))))

    (it "throws when :repo-path is missing"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (expect (try (sut/ingest-git! env {:n 10}) false
                       (catch clojure.lang.ExceptionInfo _ true)))
          (finally (sut/dispose-env! env)))))

    (it "throws when path is not a git repo"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (expect (try (sut/ingest-git! env {:repo-path "/tmp"}) false
                       (catch clojure.lang.ExceptionInfo _ true)))
          (finally (sut/dispose-env! env)))))

    (it "re-ingesting same name replaces the attached Repository"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (sut/ingest-git! env {:repo-path SVAR_REPO_ROOT :repo-name "svar" :n 5})
          (let [first-repo (get @(:git-repos-atom env) "svar")]
            (sut/ingest-git! env {:repo-path SVAR_REPO_ROOT :repo-name "svar" :n 5})
            (let [second-repo (get @(:git-repos-atom env) "svar")]
              (expect (not (identical? first-repo second-repo)))
              (expect (= 1 (count @(:git-repos-atom env))))))
          (finally (sut/dispose-env! env)))))))

(defdescribe git-sci-bindings-test
  (describe "SCI sandbox after ingest-git! (single repo) — git- prefix"
    (it "does NOT resolve git tools before ingest"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (expect (nil? (resolve-in-sandbox env 'git-search-commits)))
          (expect (nil? (resolve-in-sandbox env 'git-blame)))
          (expect (nil? (resolve-in-sandbox env 'git-commit-diff)))
          (finally (sut/dispose-env! env)))))

    (it "resolves all seven git-* tools after ingest"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (sut/ingest-git! env {:repo-path SVAR_REPO_ROOT :repo-name "svar" :n 20})
          (expect (some? (resolve-in-sandbox env 'git-search-commits)))
          (expect (some? (resolve-in-sandbox env 'git-commit-history)))
          (expect (some? (resolve-in-sandbox env 'git-commits-by-ticket)))
          (expect (some? (resolve-in-sandbox env 'git-file-history)))
          (expect (some? (resolve-in-sandbox env 'git-blame)))
          (expect (some? (resolve-in-sandbox env 'git-commit-diff)))
          (expect (some? (resolve-in-sandbox env 'git-commit-parents)))
          (finally (sut/dispose-env! env)))))

    (it "git-search-commits returns commits from the DB"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (sut/ingest-git! env {:repo-path SVAR_REPO_ROOT :repo-name "svar" :n 30})
          (let [result (eval-in-sandbox env "(git-search-commits {:limit 10})")]
            (expect (vector? result))
            (expect (pos? (count result)))
            (expect (every? :commit/sha result)))
          (finally (sut/dispose-env! env)))))

    (it "single-repo path-based tools accept relative paths"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (sut/ingest-git! env {:repo-path SVAR_REPO_ROOT :repo-name "svar" :n 5})
          (let [history (eval-in-sandbox env "(git-file-history \"deps.edn\" {:n 5})")]
            (expect (vector? history))
            (expect (pos? (count history))))
          (finally (sut/dispose-env! env)))))

    (it "single-repo sha-based tools accept HEAD"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (sut/ingest-git! env {:repo-path SVAR_REPO_ROOT :repo-name "svar" :n 5})
          (let [diff (eval-in-sandbox env "(git-commit-diff \"HEAD\")")
                parents (eval-in-sandbox env "(git-commit-parents \"HEAD\")")]
            (expect (string? diff))
            (expect (pos? (count diff)))
            (expect (vector? parents)))
          (finally (sut/dispose-env! env)))))))

(defn- with-multi-repo-env
  "Create temp repo + RLM env, ingest svar AND tempy, run `f`, dispose both.
   Used by multi-repo-test because lazytest `it` blocks are evaluated lazily
   and the surrounding `try` would unwind before the test runs."
  [f]
  (let [temp (make-temp-git-repo!)]
    (try
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (sut/ingest-git! env {:repo-path SVAR_REPO_ROOT :repo-name "svar" :n 10})
          (sut/ingest-git! env {:repo-path (:path temp) :repo-name "tempy" :n 10})
          (f env temp)
          (finally (sut/dispose-env! env))))
      (finally (rm-rf! (:path temp))))))

(defdescribe multi-repo-test
  (describe "ingest-git! with multiple repos attached — auto-dispatch"
    (it "both repos are present in git-repos-atom"
      (with-multi-repo-env
        (fn [env _]
          (let [repos @(:git-repos-atom env)]
            (expect (= 2 (count repos)))
            (expect (contains? repos "svar"))
            (expect (contains? repos "tempy"))))))

    (it "git-search-commits sees commits from BOTH repos (no filter)"
      (with-multi-repo-env
        (fn [env _]
          (let [all (eval-in-sandbox env "(git-search-commits {:limit 100})")
                docs (set (map :entity/document-id all))]
            (expect (contains? docs "svar"))
            (expect (contains? docs "tempy"))))))

    (it "git-search-commits scopes to svar with :document-id"
      (with-multi-repo-env
        (fn [env _]
          (let [result (eval-in-sandbox env "(git-search-commits {:document-id \"svar\" :limit 100})")
                docs (set (map :entity/document-id result))]
            (expect (= #{"svar"} docs))))))

    (it "git-search-commits scopes to tempy with :document-id"
      (with-multi-repo-env
        (fn [env _]
          (let [result (eval-in-sandbox env "(git-search-commits {:document-id \"tempy\" :limit 100})")
                docs (set (map :entity/document-id result))]
            (expect (= #{"tempy"} docs))))))

    (it "git-blame with ABSOLUTE path inside svar routes to svar"
      (with-multi-repo-env
        (fn [env _]
          (let [abs-path (str SVAR_REPO_ROOT File/separator "deps.edn")
                code (str "(git-blame " (pr-str abs-path) " 1 3)")
                result (eval-in-sandbox env code)]
            (expect (vector? result))
            (expect (pos? (count result)))
            (expect (every? :sha result))))))

    (it "git-blame with ABSOLUTE path inside tempy routes to tempy"
      (with-multi-repo-env
        (fn [env temp]
          (let [code (str "(git-blame " (pr-str (:file-abs temp)) " 1 3)")
                result (eval-in-sandbox env code)]
            (expect (vector? result))
            (expect (pos? (count result)))))))

    (it "git-file-history with ABSOLUTE path in svar routes correctly"
      (with-multi-repo-env
        (fn [env _]
          (let [abs-path (str SVAR_REPO_ROOT File/separator "deps.edn")
                code (str "(git-file-history " (pr-str abs-path) " {:n 5})")
                result (eval-in-sandbox env code)]
            (expect (vector? result))
            (expect (pos? (count result)))))))

    (it "git-commit-diff with explicit SHA auto-dispatches to owning repo (tempy)"
      (with-multi-repo-env
        (fn [env temp]
          (let [code (str "(git-commit-diff " (pr-str (:sha temp)) ")")
                result (eval-in-sandbox env code)]
            (expect (string? result))
            (expect (pos? (count result)))))))

    (it "git-commit-parents with tempy SHA returns [] (root commit)"
      (with-multi-repo-env
        (fn [env temp]
          (let [code (str "(git-commit-parents " (pr-str (:sha temp)) ")")
                result (eval-in-sandbox env code)]
            (expect (vector? result))
            (expect (zero? (count result)))))))

    (it "git-commit-diff \"HEAD\" throws :rlm/ambiguous-ref in multi-repo"
      (with-multi-repo-env
        (fn [env _]
          (let [thrown? (try
                          (eval-in-sandbox env "(git-commit-diff \"HEAD\")")
                          false
                          (catch Exception _ true))]
            (expect thrown?)))))

    (it "git-blame with RELATIVE path in multi-repo throws :rlm/no-repo-for-path"
      (with-multi-repo-env
        (fn [env _]
          (let [thrown? (try
                          (eval-in-sandbox env "(git-blame \"deps.edn\" 1 3)")
                          false
                          (catch Exception _ true))]
            (expect thrown?)))))

    (it "git-commit-diff with unknown SHA throws :rlm/no-repo-for-sha"
      (with-multi-repo-env
        (fn [env _]
          (let [thrown? (try
                          (eval-in-sandbox env "(git-commit-diff \"deadbeef00000000000000000000000000000000\")")
                          false
                          (catch Exception _ true))]
            (expect thrown?)))))))

(defdescribe git-system-prompt-test
  (describe "build-system-prompt with :git-contexts"
    (it "omits GIT REPO block when :git-contexts is nil"
      (let [prompt (rlm-core/build-system-prompt {:has-reasoning? false})]
        (expect (not (str/includes? prompt "GIT REPO")))
        (expect (not (str/includes? prompt "git-search-commits")))))

    (it "omits GIT REPO block when :git-contexts is empty map"
      (let [prompt (rlm-core/build-system-prompt
                     {:has-reasoning? false :git-contexts {}})]
        (expect (not (str/includes? prompt "GIT REPO")))))

    (it "renders single-repo block with git- prefixed tool list"
      (let [prompt (rlm-core/build-system-prompt
                     {:has-reasoning? false
                      :git-contexts {"myrepo"
                                     {:repo-path "/tmp/fake"
                                      :repo-name "myrepo"
                                      :commits-ingested 42
                                      :head {:sha "abc123def456789"
                                             :short "abc123def456"
                                             :branch "main"}}}})]
        (expect (str/includes? prompt "GIT REPO: myrepo"))
        (expect (str/includes? prompt "/tmp/fake"))
        (expect (str/includes? prompt "abc123def456"))
        (expect (str/includes? prompt "on main"))
        (expect (str/includes? prompt "commits ingested: 42"))
        (expect (str/includes? prompt "git-search-commits"))
        (expect (str/includes? prompt "git-file-history"))
        (expect (str/includes? prompt "git-blame"))
        (expect (str/includes? prompt "git-commit-diff"))
        (expect (str/includes? prompt "git-commit-parents"))))

    (it "renders multi-repo blocks with absolute-path guidance"
      (let [prompt (rlm-core/build-system-prompt
                     {:has-reasoning? false
                      :git-contexts {"svar"
                                     {:repo-path "/x/svar"
                                      :repo-name "svar"
                                      :commits-ingested 290
                                      :head {:short "deadbeef0000" :branch "main"}}
                                     "datalevin"
                                     {:repo-path "/x/datalevin"
                                      :repo-name "datalevin"
                                      :commits-ingested 5000
                                      :head {:short "cafef00d0000" :branch "master"}}}})]
        (expect (str/includes? prompt "GIT REPO: svar"))
        (expect (str/includes? prompt "GIT REPO: datalevin"))
        (expect (str/includes? prompt "on main"))
        (expect (str/includes? prompt "on master"))
        (expect (str/includes? prompt "290"))
        (expect (str/includes? prompt "5000"))
        (expect (str/includes? prompt "ABSOLUTE path"))
        (expect (str/includes? prompt "git-blame"))))))

(defdescribe dispose-closes-repo-test
  (describe "dispose-env! cleanup"
    (it "closes every attached git repo on dispose and clears both atoms"
      (let [temp (make-temp-git-repo!)]
        (try
          (let [env (sut/create-env (stub-router) {:db :temp})]
            (sut/ingest-git! env {:repo-path SVAR_REPO_ROOT :repo-name "svar" :n 5})
            (sut/ingest-git! env {:repo-path (:path temp) :repo-name "tempy" :n 5})
            (let [repos-atom (:git-repos-atom env)
                  contexts-atom (:git-contexts-atom env)]
              (expect (= 2 (count @repos-atom)))
              (sut/dispose-env! env)
              (expect (= {} @repos-atom))
              (expect (= {} @contexts-atom))))
          (finally (rm-rf! (:path temp))))))))
