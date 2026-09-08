(ns com.blockether.svar.internal.tokenizer-startup-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [charred.api :as json]
            [lazytest.core :refer [defdescribe expect it]])
  (:import [java.util.concurrent TimeUnit]))

(defdescribe
  tokenizer-startup-test
  (it
    "loads no encodings at router startup and supports concurrent first use"
    (let
      [program
       '(do
         (require '[com.blockether.svar.internal.router :as router])
         (let
          [registry @(ns-resolve 'com.blockether.svar.internal.router 'registry)
           ^java.lang.reflect.Field field
           (.getDeclaredField (.getSuperclass (class registry)) "encodings")]
          (.setAccessible field true)
          (let
           [^java.util.Map encodings (.get field registry) before (set (.keySet encodings)) work
            (doall
             (for
              [model (take 12 (cycle ["gpt-4o" "gpt-4" "unknown-model"]))]
              (future (router/count-tokens model "Hello, world!")))) counts (mapv deref work)]
           (prn {:after (set (.keySet encodings)) :before before :counts counts})))
         (shutdown-agents))

       process
       (.start (ProcessBuilder. ^java.util.List
                                [(str (System/getProperty "java.home") "/bin/java") "-cp"
                                 (System/getProperty "java.class.path") "clojure.main" "-e"
                                 (pr-str program)]))

       output
       (future (slurp (.getInputStream process)))

       errors
       (future (slurp (.getErrorStream process)))]

      (try (expect (.waitFor process 60 TimeUnit/SECONDS))
           (expect (= 0 (.exitValue process)) @errors)
           (let [result (edn/read-string @output)]
             (expect (= #{} (:before result)))
             (expect (= (vec (repeat 12 4)) (:counts result)))
             (expect (= #{"o200k_base" "cl100k_base"} (:after result))))
           (finally (when (.isAlive process) (.destroyForcibly process)))))))

(defdescribe tokenizer-native-resources-test
             (it "ships metadata retaining tokenizer vocabularies for lazy native first use"
                 (let [metadata
                       (io/resource
                         "META-INF/native-image/com.blockether/svar/reachability-metadata.json")]
                   (expect metadata)
                   (expect (some #(= "com/knuddels/jtokkit/*.tiktoken" (get % "glob"))
                                 (get (json/read-json (slurp metadata)) "resources")))
                   (doseq [name ["cl100k_base" "o200k_base" "p50k_base" "r50k_base"]]
                     (expect (io/resource (str "com/knuddels/jtokkit/" name ".tiktoken")))))))
