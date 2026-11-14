#!/usr/bin/env bash
# D-42 negative gate: prove that a red cljs.test run reaches the shell as a
# non-zero exit code, and that the fixture stays invisible to normal gates.
#
# The fixture is hc.test.always-fails-test, whose single assertion fails only
# when HC_NEGATIVE_GATE=1.  Because the hard project rule keeps this probe
# under test/js/, the namespace file lives at
# test/js/hc/test/always_fails_test.cljs and this harness adds test/js as a
# ClojureScript source root.  No production source is compiled here: the
# fixture requires nothing but cljs.test.
#
# Three cases run against one build:
#
#   1. control / pre-D-42 shape.  A bare `node -e require(entry)` stub, with
#      HC_NEGATIVE_GATE=1.  It prints `1 failures, 0 errors.` and exits 0:
#      a fake green.  Reproducing that here is the falsification evidence
#      that the guarded runner below, not something else, is what turns a
#      red suite into a red process.  If this case ever stops exiting 0,
#      the runner contract has changed and this probe needs revisiting.
#   2. production guard / HC_NEGATIVE_GATE=1.  The same failing build goes
#      through the shipped test/js/run_schedule_ui_tests.cjs.  Its real
#      beforeExit guard must exit non-zero.  This is the production code the
#      normal gate runs; a canary copy would only test itself.
#   3. green contract / no HC_NEGATIVE_GATE.  The shipped runner and the same
#      build must exit 0 and report `0 failures, 0 errors.`.  This proves the
#      fixture is skipped unless the switch is explicit, so a normal run
#      (scripts/test-cljs-gate.sh, test/js/verify_all.sh) stays green.
#
# Exit status meaning is inverted relative to an ordinary harness: this script
# exits 0 when it has *detected* the failure.  Exit 1 means the detection
# machinery is broken (build failed, guard did not propagate, summary missing,
# or the fixture leaked into the green path).  scripts/test-cljs-gate.sh can
# therefore count it as an ordinary PASS/FAIL check.
#
# No production source and no resources/public/js are touched.
# Deliberately no `-e`: every case is judged by its own exit code, so an
# expected non-zero status must not abort the remaining cases.
set -uo pipefail

if [ "${HC_CLJS_HARNESS_TIMEOUT:-0}" != "1" ]; then
  HC_CLJS_HARNESS_TIMEOUT=1 exec timeout -k 10 600 bash "$0" "$@"
fi

cd "$(dirname "$0")/../.."

# The harnesses call `clojure` directly; the repository supplies it through
# Pixi.  verify_all.sh does the same for the same reason.
if [ -d ".pixi/envs/default/bin" ]; then
  PATH="$PWD/.pixi/envs/default/bin:$PATH"
  export PATH
fi

mkdir -p target
OUT="$(mktemp -d -p target "cljs-gate-negative.XXXXXX")"
OUT="${OUT#./}"
cleanup() {
  local status=$?
  if [ "$status" -eq 0 ]; then
    rm -rf "$OUT"
  else
    printf 'negative gate output kept for inspection: %s\n' "$OUT" >&2
  fi
  exit "$status"
}
trap cleanup EXIT

ENTRY="$(cd "$OUT" && pwd)/test.js"
CONTROL_LOG="$OUT/control.log"
PRODUCTION_LOG="$OUT/production.log"
GREEN_LOG="$OUT/green.log"
SHIPPED_RUNNER="test/js/run_schedule_ui_tests.cjs"
failures=0

fail() {
  printf 'negative gate FAILED: %s\n' "$1" >&2
  failures=$((failures + 1))
}

printf '==> compile hc.test.always-fails-test into %s\n' "$OUT"
# The React shims are compiled in only because the shipped runner rewrites the
# generated foreign-lib files; the fixture itself imports none of them.
clojure -Sdeps '{:paths ["src" "resources" "test" "test/js"]}' -M:cljs \
  -co "{:target :nodejs
       :output-to \"$ENTRY\"
       :output-dir \"$OUT\"
       :main hc.test.always-fails-test/-main
       :optimizations :none
       :language-in :ecmascript-2017
       :rewrite-polyfills false
       :npm-deps false
       :foreign-libs
       [{:file \"test/js/react_shim.js\"
         :provides [\"react\"]
         :module-type :commonjs}
        {:file \"test/js/react_dom_shim.js\"
         :provides [\"react-dom\"]
         :module-type :commonjs}
        {:file \"test/js/react_dom_server_shim.js\"
         :provides [\"react-dom/server\"]
         :module-type :commonjs}]}" \
  -c hc.test.always-fails-test >"$OUT/compile.log" 2>&1
compile_code=$?
if [ "$compile_code" -ne 0 ]; then
  cat "$OUT/compile.log" >&2
  fail "compile step exited $compile_code"
  exit 1
fi
printf '{"type":"commonjs"}\n' >"$OUT/package.json"

printf '\n==> control: bare require stub, HC_NEGATIVE_GATE=1 (expect exit 0)\n'
HC_NEGATIVE_GATE=1 node -e 'require(process.argv[1])' "$ENTRY" \
  >"$CONTROL_LOG" 2>&1
control_code=$?
printf 'control exit=%s\n' "$control_code"

if [ "$control_code" -ne 0 ]; then
  cat "$CONTROL_LOG" >&2
  fail "control stub exited $control_code, expected 0; the contrast evidence is void"
elif ! grep -Eq '[1-9][0-9]* failures?, [0-9]+ errors?\.' "$CONTROL_LOG"; then
  cat "$CONTROL_LOG" >&2
  fail "control stub exited 0 but printed no non-zero failure summary"
else
  printf 'control: observed a red summary absorbed as exit 0 (fake green reproduced)\n'
  grep -E 'FAIL in|[1-9][0-9]* failures?, [0-9]+ errors?\.' "$CONTROL_LOG" || true
fi

check_red() {
  local label="$1" log="$2" code="$3"
  if [ "$code" -eq 0 ]; then
    cat "$log" >&2
    fail "$label exited 0 on a failing suite (fake green regression)"
  elif ! grep -Eq '[1-9][0-9]* failures, [0-9]+ errors?\.' "$log"; then
    cat "$log" >&2
    fail "$label exited $code but printed no non-zero failure summary"
  elif ! grep -Eq 'FAIL in .*negative-gate-contract' "$log"; then
    cat "$log" >&2
    fail "$label exited $code without the fixture's FAIL line"
  else
    printf '%s: observed a red summary rejected with exit %s\n' "$label" "$code"
    grep -E 'FAIL in|[1-9][0-9]* failures, [0-9]+ errors?\.' "$log" || true
  fi
}

printf '\n==> production guard: %s, HC_NEGATIVE_GATE=1 (expect exit != 0)\n' \
  "$SHIPPED_RUNNER"
HC_NEGATIVE_GATE=1 node "$SHIPPED_RUNNER" "$OUT" >"$PRODUCTION_LOG" 2>&1
production_code=$?
printf 'production exit=%s\n' "$production_code"
check_red "production guard" "$PRODUCTION_LOG" "$production_code"

printf '\n==> green contract: %s without HC_NEGATIVE_GATE (expect exit 0)\n' \
  "$SHIPPED_RUNNER"
env -u HC_NEGATIVE_GATE node "$SHIPPED_RUNNER" "$OUT" >"$GREEN_LOG" 2>&1
green_code=$?
printf 'green exit=%s\n' "$green_code"

if [ "$green_code" -ne 0 ]; then
  cat "$GREEN_LOG" >&2
  fail "green path exited $green_code; the fixture leaked into a normal run"
elif ! grep -q '0 failures, 0 errors\.' "$GREEN_LOG"; then
  cat "$GREEN_LOG" >&2
  fail "green path exited 0 without a 0-failure summary"
elif ! grep -q 'skipped: HC_NEGATIVE_GATE is not 1' "$GREEN_LOG"; then
  cat "$GREEN_LOG" >&2
  fail "green path exited 0 but the fixture did not report itself skipped"
else
  printf 'green: observed the fixture skipping itself with exit 0\n'
  grep -E 'skipped:|0 failures, 0 errors\.' "$GREEN_LOG" || true
fi

printf '\n'
if [ "$failures" -ne 0 ]; then
  printf 'negative gate NOT verified: %s assertion(s) failed; logs under %s\n' \
    "$failures" "$OUT" >&2
  exit 1
fi
printf 'negative gate verified: control stub -> exit %s (fake green); production guard -> exit %s; green path -> exit %s\n' \
  "$control_code" "$production_code" "$green_code"
printf 'logs discarded: %s\n' "$OUT"
exit 0
