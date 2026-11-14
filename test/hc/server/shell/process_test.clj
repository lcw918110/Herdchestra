(ns hc.server.shell.process-test
  "Real-process assertions for `stop-tree!`.

  The shell session tests exercise the stop path through `shell/request-stop!`,
  and the only stop-tree! test there redefines the function, so nothing
  asserted that the managed tree actually ends.  These tests start real
  `sh` trees and check the operating system afterwards."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.shell.process :as process])
  (:import (java.lang ProcessHandle)
           (java.nio.file Files Path Paths)
           (java.nio.file.attribute FileAttribute)))

;; ---------------------------------------------------------------------------
;; helpers

(defn- temp-dir ^Path []
  (Files/createTempDirectory "hc-process-test-" (make-array FileAttribute 0)))

(defn- stat-of
  "Return the `ps` STAT string for PID, or nil when the pid is gone.

  A zombie is reported as dead: it holds no code and cannot do work."
  [pid]
  (when (and (some? pid) (pos? (long pid)))
    (let [{:keys [exit out]} (sh/sh "/bin/ps" "-o" "stat=" "-p" (str pid))
          stat (str/trim (or out ""))]
      (when (and (zero? exit) (seq stat) (not (str/starts-with? stat "Z")))
        stat))))

(defn- live?
  "True when PID is a running process.

  `ps` is authoritative here: it reports the STAT column, which separates a
  live process from the zombie left after a signal.  A ProcessHandle alone
  still reports a zombie as alive."
  [pid]
  (boolean (stat-of pid)))

(defn- wait-until
  ([pred] (wait-until pred 5000))
  ([pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (cond
         (pred) true
         (> (System/currentTimeMillis) deadline) false
         :else (do (Thread/sleep 10) (recur)))))))

(defn- read-pid [^Path file]
  (try
    (Long/parseLong (str/trim (str (slurp (str file)))))
    (catch Throwable _ nil)))

(defn- await-pid [^Path file timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [pid (read-pid file)]
        (cond
          (some? pid) pid
          (> (System/currentTimeMillis) deadline) nil
          :else (do (Thread/sleep 10) (recur)))))))

(defn- shell-process
  "Start SCRIPT as `/bin/sh -c SCRIPT` with a stdin that is already closed."
  [^Path dir script]
  (process/start! {:program "/bin/sh" :args ["-c" script]}
                  dir
                  {"PATH" "/usr/bin:/bin:/usr/sbin:/sbin"}))

(defn- force-kill!
  "Best-effort cleanup for a process tree a failing assertion left behind."
  [^java.lang.Process process pids]
  (doseq [pid pids
          :when (and pid (live? pid))]
    (try (sh/sh "/bin/kill" "-9" (str pid)) (catch Throwable _ nil)))
  (try (.destroyForcibly process) (catch Throwable _ nil)))

;; ---------------------------------------------------------------------------
;; an existing child

(deftest stop-tree-ends-a-live-descendant
  (let [dir (temp-dir)
        pid-file (.resolve dir "child.pid")
        script (str "sleep 30 & echo $! > " pid-file "; wait")
        process (shell-process dir script)
        known (atom {})
        child (await-pid pid-file 5000)]
    (try
      (is (some? child) "the shell forked its child before the stop began")
      (is (true? (live? child)) "the child is running before the stop")
      (testing "the entry snapshot is empty; only the re-scan can find the child"
        (is (= {} @known) "the caller recorded nothing"))
      (let [result (process/stop-tree! process known
                                       {:grace-ms 400 :settle-ms 50})]
        (is (true? (:signal-sent? result))
            "the stop reports that it signalled at least one handle")
        (is (= [] (:alive result))
            "the returned report lists nothing still alive")
        (is (= [] (:errors result)))
        (testing "the descendant is recorded by the re-scan, not by the caller"
          (is (contains? @known child)
              "the pid seen through ProcessHandle.descendants is in `known`"))
        (testing "no part of the tree survives the stop"
          (is (wait-until #(not (live? child)) 3000)
              "the forked `sleep` ended; a stop that only signals the parent
               leaves it running as an orphan")
          (is (not (live? child)))
          (is (not (.isAlive (.toHandle process))) "the shell ended")))
      (finally
        (force-kill! process [child])))))

;; ---------------------------------------------------------------------------
;; a child forked after the stop began

(deftest settle-window-reaches-a-child-forked-during-the-stop
  ;; `sh` forks its child a few milliseconds after start.  A stop that scans
  ;; once and signals immediately kills the parent before the fork, so the
  ;; child is never born and never observed; the settle window exists so the
  ;; stop sees the tree the caller is actually responsible for.
  (let [dir (temp-dir)
        pid-file (.resolve dir "late.pid")
        script (str "sleep 0.25; sleep 30 & echo $! > " pid-file "; wait")
        process (shell-process dir script)
        known (atom {})
        started (System/currentTimeMillis)]
    (try
      (let [result (process/stop-tree! process known
                                       {:grace-ms 400 :settle-ms 1000})
            elapsed (- (System/currentTimeMillis) started)
            child (read-pid pid-file)]
        (is (>= elapsed 1000)
            "the settle window delays the first signal instead of scanning once")
        (is (some? child)
            "the shell finished forking inside the settle window")
        (testing "the late child is known to the stop"
          (is (contains? @known child)))
        (is (true? (:signal-sent? result)))
        (is (= [] (:alive result)))
        (testing "the late child does not outlive the parent"
          (is (wait-until #(not (live? child)) 3000)
              "a child forked during the stop is still signalled")))
      (finally
        (force-kill! process [(read-pid pid-file)])))))

;; ---------------------------------------------------------------------------
;; one signal per pid

(deftest one-term-per-pid-and-no-signal-downgrade
  ;; The parent traps TERM and keeps running, so the grace window expires and
  ;; the stop must escalate to the forcible signal.  Re-scanning the tree
  ;; happens about twenty times in a 400 ms grace; the per-pid ledger is what
  ;; keeps that from becoming twenty TERMs, and it is what keeps the later
  ;; pass from sending TERM again after SIGKILL.
  (let [dir (temp-dir)
        term-log (.resolve dir "term.log")
        pid-file (.resolve dir "child.pid")
        script (str "trap 'echo TERM >> " term-log "' TERM; "
                    "sleep 30 & echo $! > " pid-file "; "
                    "while :; do sleep 0.05; done")
        process (shell-process dir script)
        known (atom {})
        child (await-pid pid-file 5000)]
    (try
      (is (some? child) "the child exists before the stop")
      (let [result (process/stop-tree! process known
                                       {:grace-ms 400 :settle-ms 50})]
        (is (true? (:forcibly? result))
            "a parent that ignores TERM is killed")
        (is (= [] (:alive result)))
        (is (= [] (:errors result)))
        (is (wait-until #(not (.isAlive (.toHandle process))) 3000))
        (testing "the ledger sends TERM once, not once per scan"
          (let [terms (if (Files/exists term-log (make-array java.nio.file.LinkOption 0))
                        (str/split-lines (str/trim (slurp (str term-log))))
                        [])]
            (is (= 1 (count terms))
                "the rescan repeats over the grace window but the pid ledger
                 suppresses the duplicate TERM")))
        (testing "the grandchild ends too"
          (is (wait-until #(not (live? child)) 3000))))
      (finally
        (force-kill! process [child])))))

;; ---------------------------------------------------------------------------
;; a recorded handle outlives its parent

(deftest a-recorded-child-is-signalled-after-its-parent-exits
  ;; `start-child-monitor!` records handles as it polls them.  The parent may
  ;; exit before the stop runs, and then the ppid chain the monitor walked is
  ;; already gone: the recorded handle is the only way left to reach the child.
  ;; A stop that trusts a fresh descendant scan would find nothing, and the
  ;; child would outlive the session with the write gate already released.
  (let [dir (temp-dir)
        pid-file (.resolve dir "recorded.pid")
        process (shell-process dir
                               (str "sleep 30 & echo $! > " pid-file "; exit 0"))
        known (atom {})
        monitor (process/start-child-monitor! process known 20)]
    (try
      ;; The monitor polls the descendant chain while the parent is alive, which
      ;; is the only moment that chain exists.
      (let [child (await-pid pid-file 5000)]
        (is (some? child) "the parent forked the child before exiting")
        (is (wait-until #(contains? @known child) 3000)
            "the monitor records the child while the parent is alive")
        (is (wait-until #(not (.isAlive (.toHandle process))) 3000)
            "the parent exited on its own, as a crashed session would")
        (is (true? (live? child))
            "the orphaned child keeps running; only the recorded handle reaches it")
        (testing "the stop reaches the recorded child after the parent is gone"
          (let [result (process/stop-tree! process known
                                           {:grace-ms 300 :settle-ms 20})]
            (is (true? (:signal-sent? result))
                "the recorded handle is signalled even though the fresh
                 descendant scan of a dead parent returns nothing")
            (is (= [] (:alive result)))
            (is (= [] (:errors result)))
            (is (wait-until #(not (live? child)) 3000)
                "the child does not survive the stop"))))
      (finally
        (process/stop-child-monitor! monitor)
        (force-kill! process [(read-pid pid-file)])))))