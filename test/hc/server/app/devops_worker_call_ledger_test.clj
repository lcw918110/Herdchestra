(ns hc.server.app.devops-worker-call-ledger-test
  "D-47: a Plan-worker shell invocation must survive a hard process stop.

  The real path is composed here, not mocked: `hc.server.app.devops`'s tool
  executor runs the `call` helper through a ledger port that commits
  `:worker-call/start` to an Owner state through the ordinary message
  handler.  A SIGKILL cannot be produced in a test, so the in-flight helper
  is represented by one that never returns: the record stays `:running`,
  exactly the committed fact a killed JVM leaves behind.  Recovery then has
  to turn it into `:outcome-unknown`, and the snapshot has to publish it as
  an operator check."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.owner.commands :as commands]
            [hc.compose.owner.handlers :as handlers]
            [hc.compose.owner.state :as state]
            [hc.core.ufss :as ufss]
            [hc.server.app.devops :as app-devops]
            [hc.server.app.snapshot :as snapshot]
            [hc.server.files :as files])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; ---------------------------------------------------------------------------
;; An Owner-shaped ledger port
;;
;; Production submits the same command kinds through `:submit-command!` from
;; `system.clj`'s `worker-call-command!`.  This harness applies the identical
;; handler to one state atom, so the test exercises the real commit,
;; validation, and recovery code without starting a whole server.

(defn- owner-state
  [session-id registry]
  (state/initial-state {:session-id session-id
                        :now 1000
                        :registry registry}))

(defn- ledger-port
  "Return `{:start! :finish!}` that commits through `handlers/apply-message`."
  [state-atom registry]
  (let [submit! (fn [kind payload]
                  (let [epoch (get-in @state-atom [:commands :command-epoch] 1)
                        result (handlers/apply-message
                                @state-atom
                                {:type :command
                                 :command-id (str "ledger-" (name kind) "-"
                                                  (random-uuid))
                                 :command-epoch epoch
                                 :payload (assoc payload :kind kind)}
                                {:now 2000 :registry registry})]
                    (reset! state-atom (:state result))
                    (:receipt result)))]
    {:start! (fn [entry] (submit! :worker-call/start entry))
     :finish! (fn [entry] (submit! :worker-call/finish entry))}))

(defn- temp-files-service
  []
  (let [root (str (Files/createTempDirectory
                   "hc-worker-call-"
                   (make-array FileAttribute 0)))]
    (files/service {:root root})))

(defn- wait-for
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 10) (recur))
        :else false))))

(defn- worker-call-rows
  [state]
  (filter #(str/starts-with? (str (:call-id %)) "worker-call-")
          (vals (:worker-calls state))))

;; ---------------------------------------------------------------------------
;; The acceptance test for the real path

(deftest an-interrupted-worker-call-recovers-as-outcome-unknown
  (let [session-id "d47-crash"
        registry (commands/local-registry)
        state-atom (atom (owner-state session-id registry))
        ledger (ledger-port state-atom registry)
        helper-entered (promise)
        never-returns (promise)
        helper-runs (atom 0)
        executor (:execute! (app-devops/tool-executor
                             {:files-service (temp-files-service)
                              :call-ledger ledger
                              :helpers
                              {:call (fn [_]
                                       (swap! helper-runs inc)
                                       (deliver helper-entered true)
                                       @never-returns
                                       {:status :COMPLETED :exit-code 0})}}))
        run (future
              (executor {:name "call"
                         :arguments {:name "call"
                                     :arguments {:command "rg TODO src"}}
                         :worker-id "plan-worker-1"
                         :tenure-id "plan-tenure-1"
                         :call-id "devops-call-1"}))]
    (try
      (testing "the start is committed before the helper runs"
        (is (true? (deref helper-entered 5000 false))
            "the helper must begin")
        (is (true? (wait-for #(seq (worker-call-rows @state-atom)) 5000))
            "the ledger record must exist")
        (let [row (first (worker-call-rows @state-atom))]
          (is (= 1 @helper-runs) "the helper runs exactly once")
          (is (= :running (:state row))
              "an in-flight shell is recorded as running")
          (is (= :shell (:kind row)))
          (is (= :plan-worker (:role row)))
          (is (= "plan-worker-1" (:worker-id row)))
          (is (= "call" (:member row)))
          (is (= "rg TODO src" (:label row))
              "the operator sees what to check")
          (is (= "devops-call-1" (:worker-call-id row)))
          (is (= 2000 (:created-at row)))))

      (testing "the JVM dies while the shell is in flight"
        ;; A future cannot be SIGKILLed, so it is abandoned exactly like a
        ;; killed process: no finish command is ever committed.
        (future-cancel run)
        (is (false? (realized? never-returns))))

      (let [committed @state-atom
            call-id (:call-id (first (worker-call-rows committed)))
            recovered (state/recover-state committed 9000)]
        (testing "restart recovery marks the record outcome-unknown"
          (is (= :outcome-unknown
                 (get-in recovered [:worker-calls call-id :state]))
              (str "worker call ids: "
                   (pr-str (map :call-id (worker-call-rows recovered)))))
          (is (= :outcome-unknown
                 (get-in recovered [:worker-calls call-id :outcome])))
          (is (true? (get-in recovered [:worker-calls
                                        call-id :recovered-orphan?])))
          (is (= 9000 (get-in recovered [:worker-calls call-id :finished-at])))
          (is (= 1 (count (worker-call-rows recovered))))
          (is (not (contains? (get-in recovered [:calls]) call-id))
              "a worker shell record is not a scheduler call")
          (is (empty? (:outbox recovered))
              "recovery never replays the shell"))

        (testing "recovery is idempotent"
          ;; `recover-state` always stamps `:updated-at`; only the ledger
          ;; record must be stable.  A second recovery must not move a
          ;; settled `:outcome-unknown` back to `:running` or restamp it.
          (is (= (get-in recovered [:worker-calls call-id])
                 (get-in (state/recover-state recovered 9100)
                         [:worker-calls call-id]))
              "a second recovery must not rewrite the settled record"))

        (testing "the browser publishes the recovered call as a check"
          (let [base (snapshot/baseline
                      {:sessions [{:session-id session-id :title "D-47"}]
                       :owner-snapshots
                       {session-id {:version 3 :state recovered}}
                       :active-session session-id
                       :now 9200})
                rows (get-in base [:session/by-id session-id
                                   :unresolved-calls])
                open (some #(when (= call-id (:call-id %)) %) rows)]
            (is (some? open)
                (str "unresolved rows: " (pr-str (map :call-id rows))))
            (is (true? (:outcome-unknown? open)))
            (is (= "rg TODO src" (:label open))
                "the check row keeps the command the operator must verify")
            (is (= :shell (:kind open)))
            (is (= :outcome-unknown (:last-known-stage open)))
            (is (not (contains? open :request))
                "the request stays server-side"))))
      (finally
        (future-cancel run)
        (deliver never-returns true)))))

(deftest a-settled-worker-call-is-evidence-not-an-obligation
  (let [session-id "d47-settled"
        registry (commands/local-registry)
        state-atom (atom (owner-state session-id registry))
        ledger (ledger-port state-atom registry)
        executor (:execute! (app-devops/tool-executor
                             {:files-service (temp-files-service)
                              :call-ledger ledger
                              :helpers
                              {:call (fn [_]
                                       {:status :COMPLETED :exit-code 0})}}))
        result (executor {:name "call"
                          :arguments {:name "call"
                                      :arguments {:command "rg TODO src"}}
                          :worker-id "plan-worker-1"
                          :tenure-id "plan-tenure-1"
                          :call-id "devops-call-1"})
        committed @state-atom
        call-id (:call-id (first (worker-call-rows committed)))
        recovered (state/recover-state committed 9000)
        base (snapshot/baseline
              {:sessions [{:session-id session-id :title "D-47"}]
               :owner-snapshots {session-id {:version 3 :state recovered}}
               :active-session session-id
               :now 9200})]
    (is (= :ok (:status result)))
    (testing "a completed helper settles its ledger record"
      (is (= :succeeded (get-in committed [:worker-calls call-id :state])))
      (is (= :COMPLETED (get-in committed [:worker-calls call-id
                                           :result :status])))
      (is (= 0 (get-in committed [:worker-calls call-id
                                  :result :exit-code]))))
    (testing "recovery leaves settled evidence alone"
      (is (= :succeeded (get-in recovered [:worker-calls call-id :state]))))
    (testing "a settled call is not an open operator check"
      (is (nil? (get-in base [:session/by-id session-id :unresolved-calls]))))))

(deftest an-unrecordable-call-does-not-run
  (let [registry (commands/local-registry)
        state-atom (atom (owner-state "d47-refused" registry))
        helper-runs (atom 0)
        executor (:execute! (app-devops/tool-executor
                             {:files-service (temp-files-service)
                              :call-ledger
                              {:start! (fn [_]
                                         {:status :refused
                                          :error {:code :OWNER_UNAVAILABLE
                                                  :message "no Owner"}})
                               :finish! (fn [_] {:status :refused})}
                              :helpers
                              {:call (fn [_]
                                       (swap! helper-runs inc)
                                       {:status :COMPLETED :exit-code 0})}}))
        result (executor {:name "call"
                          :arguments {:name "call"
                                      :arguments {:command "rg TODO src"}}})
        error (ufss/read-str (:text result))]
    (testing "an external action that cannot be recorded must not happen"
      (is (= :failed (:status result)))
      (is (zero? @helper-runs)))
    (is (= :failed (:status error)))
    (is (str/includes? (:message error) "did not record"))))
