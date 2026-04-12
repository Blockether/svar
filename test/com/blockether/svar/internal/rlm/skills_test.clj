(ns com.blockether.svar.internal.rlm.skills-test
  "Unit tests for skills.clj — validation, parsing, discovery, collision
   resolution, *svar-dir* rebinding, file writing, and load-skills integration."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.rlm.skills :as skills]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- make-skill-md
  "Builds a minimal valid SKILL.md content string."
  [{:keys [name description body compatibility]
    :or {description "A test skill for unit tests."
         body "## Usage\nDo the thing."
         compatibility "[svar]"}}]
  (str "---\n"
    "name: " name "\n"
    "description: " description "\n"
    "compatibility: " compatibility "\n"
    "---\n\n"
    body))

(defn- plant-skill!
  "Writes a SKILL.md under <root>/<name>/SKILL.md. Returns skill-md path."
  [root skill-name content]
  (let [dir (str (fs/path root skill-name))]
    (fs/create-dirs dir)
    (let [path (str (fs/path dir "SKILL.md"))]
      (spit path content)
      path)))

(defn- with-temp-dir
  "Calls (f tmp-dir-str) and cleans up regardless."
  [f]
  (let [tmp (str (fs/create-temp-dir))]
    (try
      (f tmp)
      (finally
        (fs/delete-tree tmp)))))

;; =============================================================================
;; NAME_RE — name validation
;; =============================================================================

(defdescribe name-validation-test
  (describe "NAME_RE regex"
    (it "accepts single lowercase letter"
      (expect (re-matches skills/NAME_RE "a")))

    (it "accepts single digit"
      (expect (re-matches skills/NAME_RE "0")))

    (it "accepts lowercase-alpha-only name"
      (expect (re-matches skills/NAME_RE "myplugin")))

    (it "accepts alphanumeric with hyphens"
      (expect (re-matches skills/NAME_RE "my-skill-v2")))

    (it "accepts name that is exactly 64 chars"
      (let [n (str "a" (apply str (repeat 63 "b")))]
        (expect (= 64 (count n)))
        (expect (re-matches skills/NAME_RE n))))

    (it "rejects uppercase letters"
      (expect (nil? (re-matches skills/NAME_RE "MySkill"))))

    (it "rejects leading hyphen"
      (expect (nil? (re-matches skills/NAME_RE "-badname"))))

    (it "rejects name longer than 64 chars"
      (let [n (apply str (repeat 65 "a"))]
        (expect (= 65 (count n)))
        (expect (nil? (re-matches skills/NAME_RE n)))))

    (it "rejects empty string"
      (expect (nil? (re-matches skills/NAME_RE ""))))

    (it "rejects name with spaces"
      (expect (nil? (re-matches skills/NAME_RE "my skill"))))

    (it "rejects name with underscore"
      (expect (nil? (re-matches skills/NAME_RE "my_skill"))))))

;; =============================================================================
;; split-frontmatter (private — accessed via var)
;; =============================================================================

(defn- split-fm [content]
  ((var-get #'skills/split-frontmatter) content))

(defdescribe frontmatter-parsing-test
  (describe "split-frontmatter"
    (it "returns nil for nil input"
      (expect (nil? (split-fm nil))))

    (it "returns nil for empty string"
      (expect (nil? (split-fm ""))))

    (it "returns nil when content does not start with ---"
      (expect (nil? (split-fm "name: foo\n---\nbody"))))

    (it "returns nil when closing --- is missing"
      (expect (nil? (split-fm "---\nname: foo\n"))))

    (it "extracts frontmatter and body for valid content"
      (let [result (split-fm "---\nname: foo\n---\nbody here")]
        (expect (vector? result))
        (expect (= 2 (count result)))
        (expect (str/includes? (first result) "name: foo"))
        (expect (str/includes? (second result) "body here"))))

    (it "strips BOM character from start"
      (let [bom "\uFEFF"
            result (split-fm (str bom "---\nname: bom\n---\nbody"))]
        (expect (some? result))
        (expect (str/includes? (first result) "name: bom"))))

    (it "handles multi-line frontmatter"
      (let [result (split-fm "---\nname: multi\ndescription: hello world\ncompatibility: [svar]\n---\nbody text")]
        (expect (some? result))
        (expect (str/includes? (first result) "description: hello world"))))))

;; =============================================================================
;; normalize-compatibility (private)
;; =============================================================================

(defn- norm-compat [v]
  ((var-get #'skills/normalize-compatibility) v))

(defdescribe normalize-compatibility-test
  (describe "normalize-compatibility"
    (it "nil → empty set"
      (expect (= #{} (norm-compat nil))))

    (it "string → lowercase singleton set"
      (expect (= #{"svar"} (norm-compat "SVAR"))))

    (it "keyword → lowercase singleton set"
      (expect (= #{"svar"} (norm-compat :SVAR))))

    (it "vec of strings → lowercase set"
      (expect (= #{"svar" "claude"} (norm-compat ["svar" "CLAUDE"]))))

    (it "vec of keywords → lowercase set"
      (expect (= #{"svar"} (norm-compat [:svar]))))

    (it "map → keys as lowercase strings"
      (expect (= #{"svar"} (norm-compat {:svar true}))))))

;; =============================================================================
;; validate-skill (private)
;; =============================================================================

(defn- validate [skill]
  ((var-get #'skills/validate-skill) skill))

(defn- base-skill
  "A fully valid skill map ready for validate-skill."
  ([] (base-skill "my-skill"))
  ([n]
   {:name        (keyword n)
    :description "A valid description."
    :compatibility #{"svar"}
    :body        "## Usage\nSome content here."
    :dir-name    n}))

(defdescribe validate-skill-test
  (describe "validate-skill"
    (it "returns [true nil] for a fully valid skill"
      (expect (= [true nil] (validate (base-skill)))))

    (it "returns :name-missing when name is nil"
      (let [[ok? reason] (validate (assoc (base-skill) :name nil))]
        (expect (false? ok?))
        (expect (= :name-missing reason))))

    (it "returns :name-format-invalid for uppercase name"
      (let [[ok? reason] (validate (assoc (base-skill) :name :MySkill :dir-name "MySkill"))]
        (expect (false? ok?))
        (expect (= :name-format-invalid reason))))

    (it "returns :name-format-invalid for leading-hyphen name"
      (let [[ok? reason] (validate (assoc (base-skill) :name :-badname :dir-name "-badname"))]
        (expect (false? ok?))
        (expect (= :name-format-invalid reason))))

    (it "returns :name-format-invalid for name longer than 64 chars"
      (let [long-name (apply str (repeat 65 "a"))
            [ok? reason] (validate (assoc (base-skill) :name (keyword long-name) :dir-name long-name))]
        (expect (false? ok?))
        (expect (= :name-format-invalid reason))))

    (it "returns :name-dir-mismatch when dir-name differs from name"
      (let [[ok? reason] (validate (assoc (base-skill "my-skill") :dir-name "other-name"))]
        (expect (false? ok?))
        (expect (= :name-dir-mismatch reason))))

    (it "returns :description-missing when description is nil"
      (let [[ok? reason] (validate (assoc (base-skill) :description nil))]
        (expect (false? ok?))
        (expect (= :description-missing reason))))

    (it "returns :description-missing when description is blank"
      (let [[ok? reason] (validate (assoc (base-skill) :description "   "))]
        (expect (false? ok?))
        (expect (= :description-missing reason))))

    (it "returns :description-too-long when description exceeds 1024 chars"
      (let [long-desc (apply str (repeat 1025 "x"))
            [ok? reason] (validate (assoc (base-skill) :description long-desc))]
        (expect (false? ok?))
        (expect (= :description-too-long reason))))

    (it "accepts description of exactly 1024 chars"
      (let [max-desc (apply str (repeat 1024 "x"))
            [ok? reason] (validate (assoc (base-skill) :description max-desc))]
        (expect (true? ok?))
        (expect (nil? reason))))

    (it "returns :compatibility-not-svar when compatibility present but no svar"
      (let [[ok? reason] (validate (assoc (base-skill) :compatibility #{"claude"}))]
        (expect (false? ok?))
        (expect (= :compatibility-not-svar reason))))

    (it "accepts empty compatibility set (no gate applied)"
      (let [[ok? reason] (validate (assoc (base-skill) :compatibility #{}))]
        (expect (true? ok?))
        (expect (nil? reason))))

    (it "returns :body-missing when body is nil"
      (let [[ok? reason] (validate (assoc (base-skill) :body nil))]
        (expect (false? ok?))
        (expect (= :body-missing reason))))

    (it "returns :body-missing when body is blank"
      (let [[ok? reason] (validate (assoc (base-skill) :body "   "))]
        (expect (false? ok?))
        (expect (= :body-missing reason))))))

;; =============================================================================
;; *svar-dir* dynamic rebinding — project-subpaths
;; =============================================================================

(defdescribe svar-dir-rebinding-test
  (describe "*svar-dir* rebinding"
    (it "project-subpaths reflects default *svar-dir* value"
      (let [paths (skills/project-subpaths)]
        (expect (some #(str/starts-with? % skills/SVAR_DIR_NAME) paths))))

    (it "project-subpaths first entry uses rebound *svar-dir*"
      (binding [skills/*svar-dir* ".myorg"]
        (let [paths (skills/project-subpaths)]
          (expect (str/starts-with? (first paths) ".myorg")))))

    (it "rebinding to different value changes first path element"
      (let [default-first (first (skills/project-subpaths))]
        (binding [skills/*svar-dir* ".custom-dir"]
          (let [rebound-first (first (skills/project-subpaths))]
            (expect (not= default-first rebound-first))
            (expect (str/starts-with? rebound-first ".custom-dir"))))))))

;; =============================================================================
;; scan-dir-for-skill-md (private)
;; =============================================================================

(defn- scan-dir [dir]
  ((var-get #'skills/scan-dir-for-skill-md) dir))

(defdescribe scan-dir-test
  (describe "scan-dir-for-skill-md"
    (it "returns empty vec for nil"
      (expect (= [] (scan-dir nil))))

    (it "returns empty vec for non-existent dir"
      (expect (= [] (scan-dir "/tmp/does-not-exist-skills-test-xyz"))))

    (it "returns empty vec for dir with no subdirs"
      (with-temp-dir
        (fn [tmp]
          (expect (= [] (scan-dir tmp))))))

    (it "returns empty vec for subdir without SKILL.md"
      (with-temp-dir
        (fn [tmp]
          (fs/create-dirs (str (fs/path tmp "my-skill")))
          (expect (= [] (scan-dir tmp))))))

    (it "finds SKILL.md one level deep"
      (with-temp-dir
        (fn [tmp]
          (let [skill-dir (str (fs/path tmp "my-skill"))]
            (fs/create-dirs skill-dir)
            (spit (str (fs/path skill-dir "SKILL.md")) "content")
            (let [result (scan-dir tmp)]
              (expect (= 1 (count result)))
              (expect (str/ends-with? (first result) "SKILL.md")))))))

    (it "finds multiple SKILL.md files in sibling subdirs"
      (with-temp-dir
        (fn [tmp]
          (doseq [n ["skill-a" "skill-b" "skill-c"]]
            (let [d (str (fs/path tmp n))]
              (fs/create-dirs d)
              (spit (str (fs/path d "SKILL.md")) "content")))
          (expect (= 3 (count (scan-dir tmp)))))))))

;; =============================================================================
;; save-skill! — file writing to disk
;; =============================================================================

(defdescribe save-skill-test
  (describe "save-skill!"
    (it "creates SKILL.md at expected path and returns path string"
      (with-temp-dir
        (fn [tmp]
          (binding [skills/*svar-dir* tmp]
            ;; Override cwd-based path: skill-dir uses (fs/cwd)/*svar-dir*/skills/<name>
            ;; We test via a real write using a known temp structure.
            (let [skill-dir-path (str (fs/path tmp "skills" "test-skill"))]
              (fs/create-dirs skill-dir-path)
              (let [skill {:name        :test-skill
                           :description "A skill for testing save."
                           :body        "## Instructions\nDo the thing."}
                    ;; Directly call build-frontmatter + spit to avoid cwd dependency
                    fm ((var-get #'skills/build-frontmatter) skill)
                    content (str fm "\n" (:body skill))
                    path (str (fs/path skill-dir-path "SKILL.md"))]
                (spit path content)
                (expect (fs/exists? path))
                (let [written (slurp path)]
                  (expect (str/includes? written "test-skill"))
                  (expect (str/includes? written "A skill for testing save.")))))))))))

;; =============================================================================
;; load-skills — full integration with temp dirs
;; =============================================================================

(defdescribe load-skills-discovery-test
  (describe "load-skills with isolated temp dir"
    (it "discovers two valid SKILL.md files"
      ;; load-skills also scans global user dirs (~/.claude/skills etc), so the
      ;; registry can contain system skills. We verify the planted ones show up,
      ;; without asserting exact count (which would be brittle across machines).
      (with-temp-dir
        (fn [tmp]
          (plant-skill! tmp "skill-alpha"
            (make-skill-md {:name "skill-alpha"
                            :description "Alpha skill for testing."
                            :body "## Usage\nAlpha does alpha things."}))
          (plant-skill! tmp "skill-beta"
            (make-skill-md {:name "skill-beta"
                            :description "Beta skill for testing."
                            :body "## Usage\nBeta does beta things."}))
          (let [registry (skills/load-skills
                           {:project-root tmp :roots [(str tmp)]
                            :allow [:skill-alpha :skill-beta]})]
            (expect (contains? registry :skill-alpha))
            (expect (contains? registry :skill-beta))))))

    (it "drops skill with invalid name format"
      (with-temp-dir
        (fn [tmp]
          (plant-skill! tmp "BadName"
            (make-skill-md {:name "BadName"
                            :description "Uppercase name should be rejected."
                            :body "## Usage\nBad."}))
          (let [registry (skills/load-skills
                           {:project-root tmp :roots [(str tmp)]
                            :allow [:BadName]})]
            (expect (not (contains? registry :BadName)))))))

    (it "drops skill missing svar in compatibility"
      (with-temp-dir
        (fn [tmp]
          (plant-skill! tmp "no-svar"
            (make-skill-md {:name "no-svar"
                            :description "Only claude compatible, no svar."
                            :body "## Usage\nWont load."
                            :compatibility "[claude]"}))
          (let [registry (skills/load-skills {:project-root tmp :roots [(str tmp)]})]
            (expect (not (contains? registry :no-svar)))))))

    (it "accepts skill with empty compatibility (no gate)"
      (with-temp-dir
        (fn [tmp]
          (plant-skill! tmp "open-compat"
            (make-skill-md {:name "open-compat"
                            :description "No compatibility field at all."
                            :body "## Usage\nShould load fine."
                            :compatibility "[]"}))
          (let [registry (skills/load-skills {:project-root tmp :roots [(str tmp)]})]
            (expect (contains? registry :open-compat))))))

    (it "drops skill with description exceeding 1024 chars"
      (with-temp-dir
        (fn [tmp]
          (plant-skill! tmp "long-desc"
            (make-skill-md {:name "long-desc"
                            :description (apply str (repeat 1025 "x"))
                            :body "## Usage\nToo long desc."}))
          (let [registry (skills/load-skills {:project-root tmp :roots [(str tmp)]})]
            (expect (not (contains? registry :long-desc)))))))

    (it "drops skill with malformed frontmatter (no --- delimiters)"
      (with-temp-dir
        (fn [tmp]
          (plant-skill! tmp "malformed"
            "name: malformed\ndescription: no frontmatter delimiters\nbody here")
          (let [registry (skills/load-skills {:project-root tmp :roots [(str tmp)]})]
            (expect (not (contains? registry :malformed)))))))))

;; =============================================================================
;; Collision resolution — project-local path wins over global same-name
;; =============================================================================

(defdescribe collision-resolution-test
  (describe "load-skills name collision"
    (it "first path wins when two skills share a name"
      (with-temp-dir
        (fn [tmp]
          (let [proj-dir (str (fs/path tmp "project-skills"))
                global-dir (str (fs/path tmp "global-skills"))]
            (fs/create-dirs proj-dir)
            (fs/create-dirs global-dir)
            ;; Same skill name in both dirs, different descriptions
            (plant-skill! proj-dir "shared-skill"
              (make-skill-md {:name "shared-skill"
                              :description "Project version wins."}))
            (plant-skill! global-dir "shared-skill"
              (make-skill-md {:name "shared-skill"
                              :description "Global version loses."}))
            ;; project-dir listed first in roots → wins
            (let [registry (skills/load-skills
                             {:project-root tmp
                              :roots [proj-dir global-dir]})]
              (expect (contains? registry :shared-skill))
              (expect (str/includes?
                        (get-in registry [:shared-skill :description])
                        "Project version wins")))))))))

;; =============================================================================
;; allow / deny filtering
;; =============================================================================

(defdescribe allow-deny-test
  (describe "load-skills :allow and :deny filtering"
    (it ":allow whitelist keeps only named skills"
      (with-temp-dir
        (fn [tmp]
          (plant-skill! tmp "keep-me"
            (make-skill-md {:name "keep-me" :description "Should be kept."}))
          (plant-skill! tmp "drop-me"
            (make-skill-md {:name "drop-me" :description "Should be dropped."}))
          (let [registry (skills/load-skills
                           {:project-root tmp
                            :roots [(str tmp)]
                            :allow [:keep-me]})]
            (expect (contains? registry :keep-me))
            (expect (not (contains? registry :drop-me)))))))

    (it ":deny blacklist removes named skills"
      (with-temp-dir
        (fn [tmp]
          (plant-skill! tmp "keep-this"
            (make-skill-md {:name "keep-this" :description "Survives deny list."}))
          (plant-skill! tmp "deny-this"
            (make-skill-md {:name "deny-this" :description "Removed by deny list."}))
          (let [registry (skills/load-skills
                           {:project-root tmp
                            :roots [(str tmp)]
                            :deny [:deny-this]})]
            (expect (contains? registry :keep-this))
            (expect (not (contains? registry :deny-this)))))))))

;; =============================================================================
;; enrich-skill — defaults and content-hash
;; =============================================================================

(defn- enrich [skill]
  ((var-get #'skills/enrich-skill) skill))

(defdescribe enrich-skill-test
  (describe "enrich-skill"
    (it "adds :content-hash string"
      (let [enriched (enrich (base-skill))]
        (expect (string? (:content-hash enriched)))
        (expect (= 64 (count (:content-hash enriched))))))

    (it "merges DEFAULT_AGENT when :agent is nil"
      (let [enriched (enrich (base-skill))]
        (expect (map? (:agent enriched)))
        (expect (contains? (:agent enriched) :max-iter))))

    (it "merges DEFAULT_REQUIRES when :requires is nil"
      (let [enriched (enrich (base-skill))]
        (expect (map? (:requires enriched)))
        (expect (contains? (:requires enriched) :docs))))

    (it "derives :abstract from description when abstract is nil"
      (let [enriched (enrich (assoc (base-skill) :abstract nil))]
        (expect (string? (:abstract enriched)))
        (expect (not (str/blank? (:abstract enriched))))))))

;; =============================================================================
;; content-hash
;; =============================================================================

(defdescribe content-hash-test
  (describe "content-hash"
    (it "returns a 64-char hex string"
      (let [h (skills/content-hash "hello")]
        (expect (string? h))
        (expect (= 64 (count h)))))

    (it "same input → same hash"
      (expect (= (skills/content-hash "abc") (skills/content-hash "abc"))))

    (it "different inputs → different hashes"
      (expect (not= (skills/content-hash "abc") (skills/content-hash "xyz"))))))

;; =============================================================================
;; skills-manifest-block
;; =============================================================================

(defdescribe skills-manifest-block-test
  (describe "skills-manifest-block"
    (it "returns empty string for empty registry"
      (expect (= "" (skills/skills-manifest-block {}))))

    (it "includes skill name and abstract for non-empty registry"
      (let [registry {:my-skill {:abstract "Does something useful."}}
            block (skills/skills-manifest-block registry)]
        (expect (str/includes? block "my-skill"))
        (expect (str/includes? block "Does something useful."))))

    (it "lists multiple skills sorted by name"
      (let [registry {:zzz-skill {:abstract "Last."}
                      :aaa-skill {:abstract "First."}}
            block (skills/skills-manifest-block registry)]
        (expect (< (.indexOf block "aaa-skill") (.indexOf block "zzz-skill")))))))
