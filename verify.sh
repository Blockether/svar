#!/usr/bin/env bash
# verify.sh — Automated verification for svar
#
# Usage:
#   ./verify.sh              # Full verification (default)
#   ./verify.sh --quick      # Format + lint only (no build/test)
#
# Each step writes:
#   .verification/<step>.log   — stdout + stderr
#   .verification/<step>.code  — exit code (0=pass, non-zero=fail, skip=skipped)
#   .verification/summary.log  — final report
#
# On failure: stops at the failed step. Fix the issue and re-run.
# The .verification/ directory is gitignored — never committed.

set -uo pipefail

VERIFY_DIR=".verification"
rm -rf "$VERIFY_DIR"
mkdir -p "$VERIFY_DIR"

TOTAL=0
PASSED=0
SKIPPED=0
FAILED_STEP=""

# --- Colors (disabled when piped) ---
if [ -t 1 ]; then
  RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
  BLUE='\033[0;34m'; BOLD='\033[1m'; NC='\033[0m'
else
  RED=''; GREEN=''; YELLOW=''; BLUE=''; BOLD=''; NC=''
fi

# --- Step runner ---
step() {
  local name="$1" desc="$2"
  shift 2
  TOTAL=$((TOTAL + 1))
  printf "${BLUE}[%02d]${NC} %-45s " "$TOTAL" "$desc"

  if "$@" > "$VERIFY_DIR/$name.log" 2>&1; then
    echo "0" > "$VERIFY_DIR/$name.code"
    PASSED=$((PASSED + 1))
    printf "${GREEN}PASS${NC}\n"
    return 0
  else
    local code=$?
    echo "$code" > "$VERIFY_DIR/$name.code"
    FAILED_STEP="$name"
    printf "${RED}FAIL${NC} (exit $code)\n"
    printf "  ${RED}see: .verification/$name.log${NC}\n"
    echo ""
    tail -20 "$VERIFY_DIR/$name.log" | sed 's/^/    /'
    echo ""
    return 1
  fi
}

# --- Skip a step ---
skip() {
  local name="$1" desc="$2" reason="$3"
  TOTAL=$((TOTAL + 1))
  SKIPPED=$((SKIPPED + 1))
  PASSED=$((PASSED + 1))
  printf "${BLUE}[%02d]${NC} %-45s ${YELLOW}SKIP${NC} (%s)\n" "$TOTAL" "$desc" "$reason"
  echo "skip" > "$VERIFY_DIR/$name.code"
  echo "Skipped: $reason" > "$VERIFY_DIR/$name.log"
}

# --- Summary ---
summary() {
  local failed=$((TOTAL - PASSED))
  echo ""
  if [ $failed -eq 0 ]; then
    printf "${GREEN}${BOLD}All %d steps passed" "$TOTAL"
    [ $SKIPPED -gt 0 ] && printf " (%d skipped)" "$SKIPPED"
    printf "${NC}\n"
  else
    printf "${RED}${BOLD}Failed at step: %s (%d of %d passed)${NC}\n" "$FAILED_STEP" "$PASSED" "$TOTAL"
    printf "Fix the issue, then re-run: ${BOLD}./verify.sh${NC}\n"
  fi
  echo ""

  {
    echo "verify.sh — $(date -Iseconds)"
    echo "total=$TOTAL passed=$PASSED failed=$failed skipped=$SKIPPED"
    [ -n "$FAILED_STEP" ] && echo "stopped_at=$FAILED_STEP"
    echo ""
    for f in "$VERIFY_DIR"/*.code; do
      [ -f "$f" ] || continue
      local sname
      sname=$(basename "$f" .code)
      [ "$sname" = "summary" ] && continue
      printf "  %-25s %s\n" "$sname" "$(cat "$f")"
    done
  } > "$VERIFY_DIR/summary.log"

  [ $failed -eq 0 ]
}

# =============================================================================
# Custom step functions
# =============================================================================

# Lint: info-level diagnostics are OK (public API vars flagged unused).
# Only fail on error or warning (after filtering known false positives).
_lint() {
  local output
  output=$(clojure-lsp diagnostics --raw 2>&1) || true
  echo "$output"

  # Filter known false positives:
  #   - unused-private-var on fns accessed via #'var from tests
  #   - unused-private-var on helpers kept for upcoming features
  local filtered
  filtered=$(echo "$output" | grep -E ": (error|warning):" \
    || true)

  if [ -n "$filtered" ]; then
    echo ""
    echo "FAILED: Lint errors or warnings found:"
    echo "$filtered"
    return 1
  fi
  echo ""
  echo "OK: no actionable lint errors or warnings"
}

# Test with sanity check on test counts.
_test_unit() {
  local output code=0
  output=$(make test 2>&1) || code=$?
  echo "$output"
  [ $code -ne 0 ] && return $code

  # Sanity: lazytest should run ~830 cases (fail if <500 — catches silent ns loading failures)
  local lt_count
  lt_count=$(echo "$output" | grep -oE 'Ran [0-9]+ test cases' | grep -oE '[0-9]+' | tail -1 || true)
  if [ -n "$lt_count" ] && [ "$lt_count" -lt 500 ]; then
    echo ""
    echo "WARNING: Only $lt_count lazytest cases (expected ~830). Possible subset run."
    return 1
  fi
  if [ -n "$lt_count" ]; then
    echo ""
    echo "Test count: $lt_count cases (threshold: 500)"
  fi
}

# README doctests
_test_readme() {
  local output code=0
  output=$(make test-readme 2>&1) || code=$?
  echo "$output"
  return $code
}

# Secret scan against base branch.
_secret_scan() {
  local base
  base=$(git merge-base HEAD origin/main 2>/dev/null || git merge-base HEAD main 2>/dev/null || echo "")
  if [ -z "$base" ]; then
    echo "No base branch found — scanning staged + unstaged changes"
    local hits
    hits=$(git diff HEAD 2>/dev/null | grep -iE "(sk-|sk_live|api_key\s*=\s*\S{8}|AKIA[0-9A-Z]{16}|ghp_|password\s*=\s*['\"][^'\"]{8})" || true)
    if [ -n "$hits" ]; then
      echo "FAILED: Potential secrets in diff:"
      echo "$hits"
      return 1
    fi
    echo "No secrets found"
    return 0
  fi
  local hits
  hits=$(git diff "$base"..HEAD | grep -iE "(sk-|sk_live|api_key\s*=\s*\S{8}|AKIA[0-9A-Z]{16}|ghp_|password\s*=\s*['\"][^'\"]{8})" || true)
  if [ -n "$hits" ]; then
    echo "FAILED: Potential secrets in diff:"
    echo "$hits"
    return 1
  fi
  echo "No secrets found"
}

# =============================================================================
# Verification modes
# =============================================================================

verify_quick() {
  printf "\n${BOLD}Quick verification (format + lint)${NC}\n\n"
  step "format" "Format source"    make format || return 1
  step "lint"   "Lint diagnostics" _lint       || return 1
  summary
}

verify_full() {
  printf "\n${BOLD}Full verification${NC}\n\n"

  # 1. Format (must run BEFORE tests — format changes must be tested)
  step "format"       "Format source"                    make format       || return 1

  # 2. Lint (errors/warnings fail, info is OK)
  step "lint"         "Lint diagnostics"                 _lint             || return 1

  # 3. Compile Java sources
  step "compile-java" "Compile Java sources"             make compile-java || return 1

  # 4. Unit tests (with count sanity check)
  step "test"         "Unit tests (lazytest)"            _test_unit        || return 1

  # 5. README doctests
  step "test-readme"  "README doctests"                  _test_readme      || return 1

  # 6. Git hygiene (conflict markers, trailing whitespace)
  step "git-check"    "Git hygiene (markers, whitespace)" git diff --check || return 1

  # 7. Secret scan
  step "secrets"      "Secret scan"                      _secret_scan      || return 1

  summary
}

# =============================================================================
# Entry point
# =============================================================================

MODE="${1:-full}"

case "$MODE" in
  --quick|-q)   verify_quick ;;
  --full|-f|*)  verify_full  ;;
esac
