.PHONY: test test-ff test-readme test-watch clean jar install deploy lint compile-java prepare

prepare: compile-java

target/classes: src
	clojure -T:build compile-java
	
test: target/classes
	clojure -M:test

test-ff: target/classes
	@set -e; for ns in \
		com.blockether.svar.internal.jsonish-test \
		com.blockether.svar.internal.tokens-test \
		com.blockether.svar.internal.humanize-test \
		com.blockether.svar.internal.guard-test \
		com.blockether.svar.internal.config-test \
		com.blockether.svar.spec-test \
		com.blockether.svar.core-test \
		com.blockether.svar.rlm.pageindex-test \
		com.blockether.svar.rlm-test \
	; do \
		echo "\n=== Testing $$ns ==="; \
		clojure -M:test -n $$ns || exit 1; \
	done

test-readme: target/classes
	clojure -M:test --dir src --md README.md

test-watch:
	clojure -M:test --watch

format: 
	clojure-lsp format

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
