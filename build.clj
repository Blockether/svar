(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.blockether/svar)
(def version
  (let [v (System/getenv "VERSION")]
    (if (and v (.startsWith v "v"))
      (subs v 1)
      (or v "0.0.1-SNAPSHOT"))))

(def class-dir "target/classes")
(def jar-file (format "target/%s.jar" (name lib)))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn compile-java [_]
  (b/javac {:src-dirs   ["src/java"]
            :class-dir  class-dir
            :basis      @basis
            :javac-opts ["--release" "11"]}))

(defn jar [_]
  (clean nil)
  (compile-java nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src/clj" "src/java"]
                :pom-data [[:description "svar — answer in Swedish. Type-safe LLM output for Clojure, inspired by BAML. Schemaless JSON parsing, token counting, guardrails, and agentic reasoning loops."]
                           [:url "https://github.com/Blockether/svar"]
                           [:licenses
                            [:license
                             [:name "Apache License, Version 2.0"]
                             [:url "https://www.apache.org/licenses/LICENSE-2.0"]]]]})
  (b/copy-dir {:src-dirs ["src/clj" "src/java"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file})
  (println "Built:" jar-file "version:" version))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
