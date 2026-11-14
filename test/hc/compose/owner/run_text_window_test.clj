(ns hc.compose.owner.run-text-window-test
  "The run text duplicates the assistant message and is bounded by a window.

  Every terminal run keeps the answer twice: once as the run's streaming
  accumulator and once as the assistant message mirrored into the
  conversation.  A session that never bounds the duplicate pays for every
  answer twice on every full-state commit, so only the newest window keeps
  the body and older runs keep a length.

  Three properties must hold.  A live run keeps its text because
  `append-run-text` accumulates into it.  An actor run keeps its text
  because `put-assistant` skips the conversation for actor calls, making
  the run record the only copy.  And the newest terminal runs keep their
  text, because the streaming, cancellation, and operator paths read a
  recent run directly."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner.state :as state]))

(defn- finish-body
  [state i body]
  (let [[state call] (state/create-call state {:kind :model
                                               :role :main
                                               :created-at i})
        call-id (:call-id call)
        run-id (state/run-id call-id "a1")
        state (state/start-run state (get-in state [:calls call-id]) "a1" i)
        state (state/append-run-text state run-id body i)]
    [state (state/finish-run state run-id :finished (inc i) {}) run-id]))

(defn- settled
  "One terminal run whose text reached the conversation."
  [i]
  (let [[_ state run-id] (finish-body (state/initial-state
                                       {:session-id "s" :now 0})
                                      i
                                      (str "answer-" i))]
    [state run-id]))

(deftest a-terminal-run-keeps-its-text-inside-the-window
  (let [[state run-id] (settled 0)
        run (get-in state [:runs run-id])]
    (is (= "answer-0" (:text run)) "the newest terminal run stays readable")
    (is (= 1 (count (:conversation state))) "the answer was mirrored")
    (is (= "answer-0" (:text (first (:conversation state))))
        "the conversation keeps the body")))

(deftest older-terminal-runs-collapse-to-a-length
  (let [window 2
        state (reduce (fn [state i]
                        (let [[_ state run-id] (finish-body state i (str "a" i))]
                          (assoc-in state [:last-run] run-id)))
                      (assoc (state/initial-state {:session-id "s" :now 0})
                             :config {:run-text-window window})
                      (range 5))
        runs (:runs state)
        with-text (->> runs
                       vals
                       (filter #(contains? % :text))
                       (map :text)
                       sort
                       vec)
        collapsed (->> runs
                       vals
                       (filter #(and (contains? % :text-length)
                                     (not (contains? % :text))))
                       count)]
    (is (= 2 (count with-text))
        "only the newest WINDOW terminal runs keep a body")
    (is (= 3 collapsed) "the older terminal runs keep only a length")
    (is (= #{"a3" "a4"} (set with-text))
        "the retained bodies are the newest ones")
    (is (= 5 (count (:conversation state)))
        "collapsing a run never removes the conversation message")))

(deftest a-live-run-keeps-its-text
  (let [state (state/initial-state {:session-id "s" :now 0})
        [state call] (state/create-call state {:kind :model
                                               :role :main
                                               :created-at 0})
        run-id (state/run-id (:call-id call) "a1")
        state (-> state
                  (state/start-run (get-in state [:calls (:call-id call)]) "a1" 0)
                  (state/append-run-text run-id "still streaming" 1)
                  (state/collapse-run-text))
        run (get-in state [:runs run-id])]
    (is (= "still streaming" (:text run))
        "a live run accumulates into its text")
    (is (not (contains? run :text-length))
        "a live run is not collapsed")))

(deftest an-actor-run-keeps-its-text
  (testing "an actor run's text is its only copy"
    (let [state (state/initial-state {:session-id "s" :now 0})
          [state call] (state/create-call state {:kind :model
                                                 :role :main
                                                 :created-at 0})
          call-id (:call-id call)
          run-id (state/run-id call-id "a1")
          state (assoc-in state [:calls call-id :actor-attempt-id] "a1")
          state (-> state
                    (state/start-run (get-in state [:calls call-id]) "a1" 0)
                    (state/append-run-text run-id "worker reply" 1)
                    (state/finish-run run-id :finished 2 {})
                    (state/collapse-run-text 0))
          run (get-in state [:runs run-id])]
      (is (state/actor-call? state call-id) "the call is an actor call")
      (is (= "worker reply" (:text run))
          "collapsing never drops the only copy of an actor reply")
      (is (empty? (:conversation state))
          "an actor reply never enters the shared conversation"))))

(deftest collapsing-is-idempotent
  (let [[state run-id] (settled 0)
        once (state/collapse-run-text state 0)
        twice (state/collapse-run-text once 0)]
    (is (not (contains? (get-in once [:runs run-id]) :text)))
    (is (= once twice) "a second collapse changes nothing")))
