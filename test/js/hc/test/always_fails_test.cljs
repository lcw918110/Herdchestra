(ns hc.test.always-fails-test
  "Deliberately failing namespace for the D-42 negative gate.

  This fixture exists only to prove that a red cljs.test run reaches the
  shell as a non-zero exit code.  It must never run in a normal gate: the
  failing assertion is gated behind HC_NEGATIVE_GATE=1, and nothing under
  scripts/ selects it except test/js/verify_negative_gate.sh.  Outside that
  switch the namespace reports zero assertions and zero failures so the
  harness can be exercised on the green path too."
  (:require [cljs.test :refer-macros [deftest is run-tests]]))

(defn negative-gate-enabled?
  []
  (and (exists? js/process)
       (= "1" (some-> js/process .-env (aget "HC_NEGATIVE_GATE")))))

(deftest negative-gate-contract
  (if (negative-gate-enabled?)
    (do
      (println "HC_NEGATIVE_GATE=1: running the deliberate failure")
      (is false
          "D-42 probe: this assertion must turn the runner exit code non-zero"))
    (println "hc.test.always-fails-test skipped: HC_NEGATIVE_GATE is not 1")))

(defn -main
  [& _]
  (run-tests 'hc.test.always-fails-test))

;; cljs.nodejscli invokes *main-cli-fn*; without this assignment the compiled
;; entry point loads and exits 0 before the tests ever run.
(set! *main-cli-fn* -main)
