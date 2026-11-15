(ns hc.compose.owner.run-shrink-contract-test
  "The contract a settled-run shrink must satisfy, written against the public
  API so it survives the implementation.

  D-49 measured that dropping a settled run's dormant metadata saves 60% of
  the :runs section, 18% of the whole document.  The fields it may drop are
  exactly those no probe and no static reader touches.  The fields it must
  keep are pinned here, each with the reason it exists.

  Written green against today's unshrunk state: every assertion is satisfied
  by the current record shape too.  That way the suite never goes red, and a
  shrink implementation that breaks a contract fails here."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner.state :as state]))

(defn- session-with-runs
  "TURNS finished runs, each with a mirrored assistant message."
  [turns]
  (reduce
   (fn [st i]
     (let [[st call] (state/create-call
                      st {:kind :model :role :plan :lane :plan
                          :agent-id :plan
                          :request {:snapshot {:messages []}}})
           attempt (keyword (str "attempt-" i))
           call (assoc call :state :running :attempt-id attempt
                       :context {:attempt-id attempt
                                 :system {:prompt-bytes 12 :modules ["test"]}}
                       :context-attempt-id attempt)
           rid (state/run-id (:call-id call) attempt)
           text (str "answer " i)]
       (-> st
           (state/put-call call)
           (state/add-attempt-node call attempt)
           (state/start-run call attempt (* 10 i))
           (state/append-run-text rid text (* 10 i))
           (state/finish-run rid :finished (* 10 (inc i))))))
   (state/initial-state {:session-id "s" :now 0})
   (range turns)))

(defn- finished-runs [st]
  (filter (fn [[_ run]] (state/run-terminal? run)) (:runs st)))

(deftest every-settled-run-keeps-the-four-fields-readers-require
  (let [st (session-with-runs 5)]
    (doseq [[id run] (finished-runs st)]
      (testing (str "run " id)
        (is (string? (:message-id run))
            "publication matches a settled run by :message-id to correct a
             stale :streaming? flag on the published message")
        (is (contains? #{:finished :failed :cancelled :outcome-unknown}
                       (:status run))
            "recover-state and every Owner scan branch on :status")
        (is (number? (:finished-at run))
            "collapse-run-text orders the retention window by :finished-at")
        (is (string? (:call-id run))
            "set-model-turn pairs a run with its call to decide whether the
             turn used tools")))))

(deftest a-settled-run-still-answers-whether-it-used-tools
  (testing "the non-emptiness of :tool-call-ids is what the turn policy asks"
    (let [st (session-with-runs 3)
          [rid run] (first (finished-runs st))
          with-tools (assoc-in st [:runs rid :tool-call-ids] ["tool-1"])]
      (is (nil? (seq (:tool-call-ids run)))
          "a run that used no tools reads as empty")
      (is (some? (seq (get-in with-tools [:runs rid :tool-call-ids])))
          "a run that used tools reads as non-empty; a shrink may replace the
           ids with a boolean but must keep this distinction"))))

(deftest a-settled-run-keeps-its-text-inside-the-window
  (let [window state/default-run-text-window
        st (session-with-runs (+ window 3))
        collapsed (state/collapse-run-text st)
        finished (sort-by (fn [[_ run]] (- (long (:finished-at run))))
                          (finished-runs collapsed))
        newest (take window finished)
        oldest (take-last 3 finished)]
    (testing "the newest window keeps a readable body"
      (doseq [[_ run] newest]
        (is (string? (:text run))
            "the operator view and the streaming path read a recent run's text")))
    (testing "older settled runs keep only their length"
      (doseq [[_ run] oldest]
        (is (nil? (:text run)))
        (is (pos? (long (or (:text-length run) 0))))))))

(deftest a-live-run-is-never-shrunk
  (let [st (session-with-runs 2)
        [st call] (state/create-call
                   st {:kind :model :role :plan :lane :plan :agent-id :plan
                       :request {:snapshot {:messages []}}})
        attempt :attempt-live
        call (assoc call :state :running :attempt-id attempt
                    :context {:attempt-id attempt} :context-attempt-id attempt)
        rid (state/run-id (:call-id call) attempt)
        st (-> st
               (state/put-call call)
               (state/add-attempt-node call attempt)
               (state/start-run call attempt 1000)
               (state/append-run-text rid "still streaming" 1001))
        run (get-in st [:runs rid])]
    (is (not (state/run-terminal? run)))
    (is (= :streaming (:status run)))
    (is (= "still streaming" (:text run))
        "append-run-text accumulates into a live run; shrinking it would
         erase the accumulation target")))

(deftest conversation-stability-ignores-settled-runs
  (testing "a state whose only runs are settled is conversation-stable"
    (is (true? (state/conversation-stable? (session-with-runs 4)))))
  (testing "one live run makes it unstable, which is why status must survive"
    (let [base (session-with-runs 2)
          [st call] (state/create-call
                     base {:kind :model :role :plan :lane :plan
                           :agent-id :plan
                           :request {:snapshot {:messages []}}})
          attempt :attempt-live
          call (assoc call :state :running :attempt-id attempt)
          rid (state/run-id (:call-id call) attempt)
          st (-> st
                 (state/put-call call)
                 (state/add-attempt-node call attempt)
                 (state/start-run call attempt 1000))]
      (is (= :started (get-in st [:runs rid :status])))
      (is (false? (state/conversation-stable? st))))))

;; ---------------------------------------------------------------------------
;; The contract above pins what a shrink must PRESERVE.  These pin what it
;; must DO, so an implementation that quietly stops shrinking fails loudly
;; instead of passing every preservation assertion by doing nothing.

(deftest a-settled-run-keeps-only-the-settled-keys
  (let [st (state/shrink-settled-runs (session-with-runs 3))
        settled (finished-runs st)]
    (is (= 3 (count settled)) "the runs are still there")
    (doseq [[id run] settled]
      (testing (str "run " id)
        (is (empty? (remove (set state/settled-run-keys) (keys run)))
            "a settled run holds no key outside the settled set")
        (is (contains? run :text-length)
            ":text-length is always present after the shrink, so a run inside
             its retention window answers its length too")
        (is (not (contains? run :role)) ":role is provenance the call still holds")
        (is (not (contains? run :context)) ":context is provenance")
        (is (not (contains? run :tool-call-ids))
            "the ids are replaced by :had-tools?")))))
(deftest shrinking-preserves-the-bit-that-tool-policy-reads
  (let [st (session-with-runs 2)
        [rid _] (first (finished-runs st))
        no-tools (state/shrink-settled-runs st)
        with-tools (state/shrink-settled-runs
                    (assoc-in st [:runs rid :tool-call-ids] ["tool-1" "tool-2"]))]
    (is (false? (get-in no-tools [:runs rid :had-tools?]))
        "a run that invoked nothing reads false")
    (is (true? (get-in with-tools [:runs rid :had-tools?]))
        "a run that invoked tools reads true, which is all the policy asks")))

(deftest shrinking-a-live-run-is-a-no-op
  (let [st (session-with-runs 2)
        [st call] (state/create-call
                   st {:kind :model :role :plan :lane :plan :agent-id :plan
                       :request {:snapshot {:messages []}}})
        attempt :attempt-live
        call (assoc call :state :running :attempt-id attempt
                    :context {:attempt-id attempt} :context-attempt-id attempt)
        rid (state/run-id (:call-id call) attempt)
        st (-> st
               (state/put-call call)
               (state/add-attempt-node call attempt)
               (state/start-run call attempt 1000)
               (state/append-run-text rid "streaming" 1001))
        shrunk (state/shrink-settled-runs st)]
    (is (= (get-in st [:runs rid]) (get-in shrunk [:runs rid]))
        "a live run is returned untouched; it still holds :role and :context")
    (is (= "streaming" (get-in shrunk [:runs rid :text])))))

(deftest shrinking-is-idempotent
  (let [once (state/shrink-settled-runs (session-with-runs 3))
        twice (state/shrink-settled-runs once)]
    (is (= once twice) "a second pass changes nothing")))

(deftest shrinking-composes-with-text-collapse
  (let [window 2
        st (-> (session-with-runs 5)
               (state/collapse-run-text window)
               (state/shrink-settled-runs))
        finished (sort-by (fn [[_ run]] (- (long (:finished-at run))))
                          (finished-runs st))
        newest (take window finished)
        oldest (take-last 3 finished)]
    (testing "the text window still holds after the shrink"
      (doseq [[_ run] newest]
        (is (string? (:text run))))
      (doseq [[_ run] oldest]
        (is (nil? (:text run)))
        (is (pos? (long (or (:text-length run) 0))))))
    (testing "and every settled run is shrunk regardless of its text"
      (doseq [[id run] finished]
        (is (empty? (remove (set state/settled-run-keys) (keys run)))
            (str "run " id " holds nothing outside the settled set"))))))