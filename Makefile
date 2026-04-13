.PHONY: test test-ff test-readme test-watch clean jar install deploy lint compile-java prepare test-allure allure-serve allure bench bench-4clojure bench-4clojure-quick bench-humaneval bench-humaneval-quick bench-list

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

test-allure: target/classes
	rm -rf allure-results allure-report
	clojure -M:test --output nested --output com.blockether.svar.allure-reporter/allure

allure-serve:
	npx --yes allure@3.2.0 open allure-report

allure: test-allure allure-serve

bench: target/classes
	clojure -M:bench -- --bench all

bench-4clojure: target/classes
	clojure -M:bench -- --bench 4clojure

bench-4clojure-quick: target/classes
	clojure -M:bench -- --bench 4clojure --limit 20

bench-humaneval: target/classes
	clojure -M:bench -- --bench humaneval

bench-humaneval-quick: target/classes
	clojure -M:bench -- --bench humaneval --limit 20

bench-list:
	clojure -M:bench -- --list
