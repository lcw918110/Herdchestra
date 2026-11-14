(ns hc.compose.owner.run-settled-census-test
  "Locks the two facts a settled-run shrink depends on.

  D-49 proposes folding a terminal run record down to identity, terminal
  state, timestamps, counts, and one 'has tool evidence' bit.  That is only
  safe while two things stay true, and both are easy to break silently:

    1. `:tool-call-ids` is read for its NON-EMPTINESS by the turn-summary
       policy (`guards.clj`), from a scan that does not filter on run status.
       A settled run must therefore still answer 'did this run use tools?'
       even after its ids move elsewhere.

    2. A settled run's `:context` has no live reader.  `start-run` copies
       `:context` from the call onto the run, and every other `:context`
       hit refers to the call record or to an agent's private context -- a
       different object.

  These assertions are about shape, not about a shrink implementation.  If
  someone later adds a reader for a settled run's `:context`, the first test
  stops explaining why the field is there and the shrink becomes unsafe."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner.state :as state]))

(defn- seeded-state
  "A state holding one model call whose run has received TEXT and finished."
  [text]
  (let [base (state/initial-state {:session-id "s" :now 0})
        [base call] (state/create-call
                     base
                     {:kind :model
                      :role :plan
                      :lane :plan
                      :agent-id :plan
                      :request {:snapshot {:messages []}}})
        call (assoc call :state :running :attempt-id :attempt-1
                    :context {:attempt-id :attempt-1
                              :system {:prompt-bytes 12
                                       :modules ["test"]}}
                    :context-attempt-id :attempt-1)
        run-id (state/run-id (:call-id call) :attempt-1)
        base (-> base
                 (state/put-call call)
                 (state/add-attempt-node call :attempt-1)
                 (state/start-run call :attempt-1 10)
                 (state/append-run-text run-id text 20))]
    [base call run-id]))

(deftest start-run-copies-the-call-context-onto-the-run
  (let [[state _ run-id] (seeded-state "answer")
        run (get-in state [:runs run-id])]
    (testing "the run record does receive the call context"
      (is (= :attempt-1 (get-in run [:context :attempt-id])))
      (is (= 12 (get-in run [:context :system :prompt-bytes])))
      (is (= :attempt-1 (:context-attempt-id run))))
    (testing "the run is live, not settled: append-run-text moves it to
             :streaming, and recover-state must still see it as unfinished"
      (is (= :streaming (:status run))))
    (testing "the assistant message carries the streamed text"
      (is (= "answer" (:text run))))))

(deftest a-settled-run-keeps-identity-and-separates-body-from-tool-evidence
  (let [[state call run-id] (seeded-state "partial ")
        state (state/finish-run state run-id :finished 30)
        run (get-in state [:runs run-id])]
    (testing "the run is terminal but keeps the identity publication needs"
      (is (= :finished (:status run)))
      (is (= (:call-id call) (:call-id run)))
      (is (= (str "assistant-" (:call-id call) "-attempt-1") (:message-id run)))
      (is (= 30 (:finished-at run)))
      (is (= 10 (:started-at run))))
    (testing "body and tool evidence are independent fields"
      (is (= "partial " (:text run)))
      (is (nil? (seq (:tool-call-ids run)))
          "an empty list reads as no tools, which is all the policy asks")
      (is (some? (seq (conj (:tool-call-ids run) "call-x")))
          "any non-empty value satisfies the policy"))))

(deftest settled-run-identity-survives-text-collapse
  (testing "collapse drops the body of an old settled run but never its
           identity, because publication matches on :message-id"
    (let [window state/default-run-text-window
          total (+ window 10)
          built (reduce
                 (fn [st i]
                   (let [[st call] (state/create-call
                                    st
                                    {:kind :model
                                     :role :plan
                                     :lane :plan
                                     :agent-id :plan
                                     :request {:snapshot {:messages []}}})
                         attempt (keyword (str "attempt-" i))
                         call (assoc call :state :running :attempt-id attempt)
                         rid (state/run-id (:call-id call) attempt)]
                     (-> st
                         (state/put-call call)
                         (state/add-attempt-node call attempt)
                         (state/start-run call attempt (* 10 i))
                         (state/append-run-text rid (str "answer " i) (* 10 i))
                         (state/finish-run rid :finished (* 10 (inc i)))))
                   )
                 (state/initial-state {:session-id "s" :now 0})
                 (range total))
          first-id "call-1"
          last-i (dec total)
          last-id (str "call-" total)
          collapsed (state/collapse-run-text built)
          old-run (get-in collapsed [:runs (state/run-id first-id :attempt-0)])
          newest (get-in collapsed
                         [:runs (state/run-id last-id
                                              (keyword (str "attempt-" last-i)))])]
      (is (= (str "assistant-" first-id "-attempt-0") (:message-id old-run))
          "an out-of-window run keeps the id publication needs")
      (is (= :finished (:status old-run)))
      (is (nil? (:text old-run)) "its body is gone")
      (is (pos? (long (or (:text-length old-run) 0)))
          "only the length survives")
      (is (= (str "answer " last-i) (:text newest))
          "a run inside the window keeps its whole body")
      (testing "collapse is idempotent"
        (is (= collapsed (state/collapse-run-text collapsed)))))))
