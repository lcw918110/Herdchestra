(ns hc.compose.owner.stream-buffer-test
  "Acceptance for the Owner-side streaming buffer (DEBT D-37 gap one).

  A streamed answer used to commit once per provider chunk: every fragment
  rewrote the whole assistant message, and every commit serializes the whole
  Owner document.  The buffer moves the uncommitted tail into Owner-thread
  state, folds it in bounded batches, and folds it unconditionally before any
  finalizing message.  Two invariants hold:

    1. Text the operator has already seen is never lost, including on Esc.
    2. A long stream costs far fewer commits than it has fragments."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.compose.owner :as owner]
            [hc.compose.owner.handlers :as handlers]
            [hc.compose.owner.state :as state]
            [hc.compose.owner.store :as store]))

(defn- wait-for
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 10) (recur))
        :else false))))

(defn- counting-store
  "Wrap INNER, counting durable document commits.

  One `commit!` is one whole-document compare-and-set, which is the cost a
  stream pays; the count is what the buffer must reduce."
  [inner]
  (let [commits (atom 0)]
    {:commits commits
     :store (reify store/Store
              (load-state [_] (store/load-state inner))
              (commit! [_ expected new-state]
                (swap! commits inc)
                (store/commit! inner expected new-state))
              (close! [_] (store/close! inner)))})) 

(defn- snapshot
  [handle]
  ((:snapshot handle)))

(defn- only-run
  [handle]
  (some-> (get-in (snapshot handle) [:state :runs]) vals first))

;; ---------------------------------------------------------------------------
;; Commit count

(deftest long-stream-commits-far-fewer-times-than-it-has-chunks
  (let [chunks 2000
        piece "0123456789"
        expected (apply str (repeat chunks piece))
        {:keys [commits store]} (counting-store (owner/memory-store))
        gateway {:start! (fn [request deliver!]
                           (dotimes [_ chunks]
                             (deliver! {:event :chunk :text piece}))
                           (deliver! {:event :completed :text expected})
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}
        handle (owner/start-owner! {:session-id "stream-count"
                                    :store store
                                    :model-gateway gateway})]
    (try
      ((:submit-command! handle)
       {:command-id "c1" :command-epoch 1
        :payload {:kind :send-message :text "count"}})
      (is (wait-for #(= :succeeded
                        (get-in (snapshot handle)
                                [:state :calls "call-1" :state]))
                    120000))
      (let [snap (snapshot handle)
            run (only-run handle)]
        (testing "the answer is complete"
          (is (= expected (:text run)))
          (is (= expected (get-in snap [:state :conversation 1 :text])))
          (is (= :finished (:status run))))
        (testing "a long stream costs far fewer commits than fragments"
          (is (< @commits chunks))
          (is (< @commits 400)
              (str "expected well under 400 commits for " chunks
                   " chunks, saw " @commits))
          (println "[stream-buffer] chunks:" chunks
                   "commits:" @commits
                   "chars:" (count expected)))
        (is (= :succeeded (get-in (snapshot handle)
                                  [:state :calls "call-1" :state])))
        (is (pos? @commits)))
      (finally
        ((:stop! handle))))))

;; ---------------------------------------------------------------------------
;; Cancellation

(deftest cancellation-keeps-the-sub-threshold-tail
  ;; The tail below the size bound is text the operator already saw, so the
  ;; Esc path must fold it before the run is finalized.  This is the exact
  ;; defect that reverted the sender-side merge attempt.
  (let [gate (promise)
        answer "half a sentence that was visible"
        gateway {:start! (fn [request deliver!]
                           (deliver! {:event :chunk :text "half a sent"})
                           (deliver! {:event :chunk :text "ence that "})
                           (deliver! {:event :chunk :text "was visible"})
                           (future
                             (deref gate 5000 nil)
                             (deliver! {:event :completed :text "never"}))
                           {:attempt-id (:attempt-id request)})
                 :cancel! (fn [_] nil)}
        handle (owner/start-owner! {:session-id "stream-cancel"
                                    :ephemeral? true
                                    :model-gateway gateway})]
    (try
      ((:submit-command! handle)
       {:command-id "c1" :command-epoch 1
        :payload {:kind :send-message :text "go"}})
      (is (wait-for #(= "half a sent" (:text (only-run handle))) 5000)
          "the opening fragment is committed at once")
      (is (= :accepted
             (:status ((:submit-command! handle)
                       {:command-id "c2" :command-epoch 1
                        :payload {:kind :stop-run :origin :operator-esc}}))))
      (is (wait-for #(= :cancelled (:status (only-run handle))) 5000))
      (let [snap (snapshot handle)
            run (only-run handle)
            message (get-in snap [:state :conversation 1])]
        (testing "the whole visible answer survives, tail included"
          (is (= answer (:text run)))
          (is (= answer (:text message))))
        (is (:cancelled? message))
        (is (false? (:streaming? message)))
        (is (= :cancelled (:status run)))
        (println "[stream-buffer] cancelled run kept" (count (:text run))
                 "chars"))
      (deliver gate true)
      (finally
        ((:stop! handle))))))


(deftest a-buffered-tail-never-resurrects-a-finished-run
  ;; A chunk that arrives after the terminal is late evidence: it must not
  ;; extend the answer, and it must not leave text waiting in the buffer.
  ;; Driven through the handler so the ordering is exact, not raced against
  ;; a live mailbox.
  (let [[initial _] (state/create-call
                     (state/initial-state {:session-id "late" :now 1})
                     {:call-id "call-1" :kind :model
                      :role :clj-plan :created-at 1})
        initial (-> initial
                    (assoc-in [:calls "call-1" :state] :running)
                    (assoc-in [:calls "call-1" :attempt-id] "attempt-1"))
        buffer (atom {})
        apply! (fn [state message now]
                 (handlers/apply-message state message
                                         {:now now :stream-buffer buffer}))
        event (fn [event] {:type :model/event
                           :event (assoc event
                                         :attempt-id "attempt-1"
                                         :model-call-id "call-1")})
        s1 (apply! initial (event {:event :chunk :text "answer"}) 2)
        run-id (-> (get-in s1 [:state :runs]) keys first)
        s2 (apply! (:state s1) (event {:event :completed :text "answer"}) 3)
        ;; The terminal is fixed, so this fragment is late evidence.
        s3 (apply! (:state s2) (event {:event :chunk :text " LATE"}) 4)
        run (get-in s3 [:state :runs run-id])
        entry (get @buffer run-id)]
    (is (= "answer" (:text run))
        "late text does not extend a finished answer")
    (is (= "answer" (get-in s3 [:state :conversation 0 :text])))
    (is (not (str/includes? (str (:text run)) "LATE")))
    (is (= 1 (count (get-in s3 [:state :runs]))))
    (is (str/blank? (state/pending-stream-text
                     (or entry (state/new-stream-entry run))))
        "nothing waits in the buffer after the fold")))

;; ---------------------------------------------------------------------------
;; Buffer primitives

(deftest buffer-primitives-are-pure-and-replay-safe
  (let [run {:text "abc" :status :streaming}
        entry (-> (state/new-stream-entry run)
                  (state/buffer-stream-text "def" 10 7)
                  (state/buffer-stream-text "ghi" 11 8))]
    (testing "a fragment merges into the entry, never the committed run"
      (is (= "defghi" (:text entry)))
      (is (= 2 (:fragments entry)))
      (is (= 3 (:start entry)) "the start mark is the committed run length"))
    (testing "a repeated message id is a replay, not a second fragment"
      (is (= entry (state/buffer-stream-text entry "ghi" 12 8))))
    (testing "the fold appends only the uncommitted tail"
      (let [[state' _] (state/fold-stream-text
                        {:runs {"r" run} :conversation []} "r" entry 12)]
        (is (= "abcdefghi" (get-in state' [:runs "r" :text])))
        (is (= "abcdefghi" (get-in state' [:conversation 0 :text])))))
    (testing "a retried fold does not append twice"
      (let [[once _] (state/fold-stream-text
                      {:runs {"r" run} :conversation []} "r" entry 12)
            entry' (state/prune-stream-entry (get-in once [:runs "r"]) entry)
            [twice _] (state/fold-stream-text once "r" entry' 13)]
        (is (= "abcdefghi" (get-in twice [:runs "r" :text])))))
    (testing "pruning drops only text the committed run covers"
      (let [entry (-> (state/new-stream-entry run)
                      (state/buffer-stream-text "def" 10 1))
            entry (state/prune-stream-entry {:text "abcdef"} entry)]
        (is (= "" (:text entry)))
        (is (= 6 (:start entry)))))
    (testing "the bounds measure the pending tail only"
      (let [entry (-> (state/new-stream-entry run)
                      (state/buffer-stream-text "xyz" 100 1))]
        (is (state/flush-due? entry 101 2 4) "size bound")
        (is (state/flush-due? entry 104 4096 4) "age bound, inclusive")
        (is (not (state/flush-due? entry 103 4096 4)))
        (is (not (state/flush-due? (assoc entry :text "") 200 1 1)))))))

(deftest a-terminal-run-keeps-buffered-text-as-evidence-only
  (let [run {:text "done" :status :finished}
        entry (-> (state/new-stream-entry run)
                  (state/buffer-stream-text " LATE" 10 1))
        state (-> (state/initial-state {:session-id "s" :now 1})
                  (assoc-in [:runs "r"] run))
        [state' entry'] (state/fold-stream-text state "r" entry 11)]
    (is (= "done" (get-in state' [:runs "r" :text])))
    (is (:late? entry') "the caller retires it instead of folding again")
    (is (some #(= " LATE" (:text %))
              (get-in state' [:runs "r" :late-events])))))