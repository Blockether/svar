.PHONY: test test-readme test-watch clean jar install deploy lint compile-java prepare

prepare: compile-java

target/classes: src
	clojure -T:build compile-java
	
test: target/classes
	clojure -M:test

test-readme: target/classes
	clojure -M:test --dir src --md README.md

test-watch:
	clojure -M:test --watch

lint:
	clojure-lsp diagnostics --raw

compile-java:
	clojure -T:build compile-java

clean:
	clojure -T:build clean
	rm -rf .cpcache .clj-kondo/.cache

jar:
	clojure -T:build jar

install:
	clojure -T:build install

deploy:
	clojure -T:build deploy
