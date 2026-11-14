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
  ;; child is never born and never observed.  The settle window delays the
  ;; first signal so the stop sees the tree the caller is responsible for.
  ;;
  ;; The window is clamped to the grace it precedes (D-48), so this asks for a
  ;; grace long enough to cover both the fork and the shutdown.
  (let [dir (temp-dir)
        pid-file (.resolve dir "late.pid")
        script (str "sleep 0.25; sleep 30 & echo $! > " pid-file "; wait")
        process (shell-process dir script)
        known (atom {})
        started (System/currentTimeMillis)]
    (try
      (let [result (process/stop-tree! process known
                                       {:grace-ms 1200 :settle-ms 900})
            elapsed (- (System/currentTimeMillis) started)
            child (read-pid pid-file)]
        (is (>= elapsed 900)
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

(deftest settle-window-never-outlasts-the-grace-it-precedes
  ;; A caller that lowers the grace below the default settle must not get a
  ;; stop dominated by a parameter it never passed, and the settle must not
  ;; eat the whole grace.  This is the clamp's contract.
  (let [dir (temp-dir)
        script "sleep 30"
        process (shell-process dir script)
        known (atom {})
        started (System/currentTimeMillis)
        result (process/stop-tree! process known
                                   {:grace-ms 120 :settle-ms 5000})
        elapsed (- (System/currentTimeMillis) started)]
    (try
      (is (true? (:signal-sent? result)))
      (is (= [] (:alive result)))
      (is (< elapsed 2500)
          "a 120 ms grace does not wait out a 5 s settle request")
      (finally
        (force-kill! process [])))))

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
  ;; `start-child-monitor!` records handles as it polls them, and only while
  ;; the ppid chain still exists.  Once the parent dies its child reparents to
  ;; init and a fresh `ProcessHandle.descendants` scan of the dead parent
  ;; returns nothing.  The recorded handle is then the only way to reach the
  ;; child; a stop that trusts a fresh scan leaves it running.
  (let [dir (temp-dir)
        pid-file (.resolve dir "recorded.pid")
        ;; The parent stays alive just long enough for the monitor to poll the
        ;; chain, then exits on its own as a crashed session would.
        process (shell-process dir
                               (str "sleep 30 & echo $! > " pid-file
                                    "; sleep 0.6"))
        known (atom {})
        monitor (process/start-child-monitor! process known 20)]
    (try
      (let [child (await-pid pid-file 5000)]
        (is (some? child) "the parent forked the child before exiting")
        (is (wait-until #(contains? @known child) 3000)
            "the monitor records the child while the parent is still alive")
        (is (wait-until #(not (.isAlive (.toHandle process))) 3000)
            "the parent exited on its own")
        (is (true? (live? child))
            "the orphaned child keeps running after its parent is gone")
        (testing "the stop reaches the recorded child although the parent died"
          (let [result (process/stop-tree! process known
                                           {:grace-ms 300 :settle-ms 20})]
            (is (true? (:signal-sent? result))
                "the recorded handle is signalled even though a fresh
                 descendant scan of the dead parent returns nothing")
            (is (= [] (:alive result)))
            (is (= [] (:errors result)))
            (is (wait-until #(not (live? child)) 3000)
                "the child does not survive the stop"))))
      (finally
        (process/stop-child-monitor! monitor)
        (force-kill! process [(read-pid pid-file)])))))