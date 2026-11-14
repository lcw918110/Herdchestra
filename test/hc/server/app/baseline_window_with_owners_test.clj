(ns hc.server.app.baseline-window-with-owners-test
  "The publication window must narrow the owner-conversation projection.

  `snapshot/baseline` builds two views of the same session:

    - `histories`, narrowed by `history-window-ids` and both window options
    - `conversations`, every visible owner's `:conversation`

  and merges the second as the base of the first.  While `conversations` was
  published whole, an owner snapshot defeated both windows: every message of
  every visible session shipped, including sessions nobody opened.  The rest
  of the suite missed it because it passes `:owner-snapshots {}`, where the
  owner projection contributes nothing.

  These assertions supply owner snapshots, which is the production case."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.snapshot :as snapshot]))

(defn- a-session [id]
  {:session-id id
   :owner-id "owner-1"
   :status :idle
   :title (str "session " id)
   :created-at 1
   :updated-at 1})

(defn- a-conversation
  "Ten messages with stable ids, shaped like real conversation entries."
  [id n]
  (mapv (fn [i]
          {:role (if (even? i) :user :assistant)
           :message-id (str id "-m" i)
           :text (str "message " i)})
        (range n)))

(defn- owner-snapshot
  "The shape `baseline` reads: {:version .. :state {:conversation .. :runs ..}}."
  [id n]
  {:version 1
   :state {:conversation (a-conversation id n)
           :runs {}}})

(defn- baseline-with-owners
  ([] (baseline-with-owners {}))
  ([extra]
   (snapshot/baseline
    (merge {:sessions [(a-session "s1") (a-session "s2")]
            :session-records [{:session-id "s1" :revision 1}
                              {:session-id "s2" :revision 1}]
            :owner-snapshots {"s1" (owner-snapshot "s1" 10)
                              "s2" (owner-snapshot "s2" 10)}
            :active-session "s1"
            :published-windows (atom {})
            :history-window-size 3}
           extra))))

(defn- published-ids
  [baseline]
  (set (keys (:message/by-id baseline))))

(deftest the-window-narrows-the-owner-conversation-projection
  (let [baseline (baseline-with-owners)]
    (testing "the active session publishes only its newest three messages"
      (is (= #{"s1-m7" "s1-m8" "s1-m9"} (published-ids baseline))
          "an owner snapshot must not defeat `history-window-size`"))
    (testing "an inactive session publishes no entity at the default window"
      (is (= #{"s1"}
             (set (map :session-id (vals (:message/by-id baseline)))))
          "the inactive default window is zero"))
    (testing "the client invariant holds: order and table agree"
      (is (= (published-ids baseline)
             (set (:message/order baseline))))
      (is (= (count (:message/order baseline))
             (count (set (:message/order baseline))))
          "each published id appears once in the order"))
    (testing "the window keeps the original conversation order"
      (is (= ["s1-m7" "s1-m8" "s1-m9"] (:message/order baseline))
          "narrowing drops the prefix; it never reorders"))))

(deftest a-fetched-window-unions-with-the-tail
  (let [windows (atom {"s1" #{"s1-m0" "s1-m1"}})
        baseline (baseline-with-owners {:published-windows windows})]
    (is (= #{"s1-m0" "s1-m1" "s1-m7" "s1-m8" "s1-m9"}
           (published-ids baseline))
        "a fetched page stays published wherever it sits")
    (is (= (published-ids baseline)
           (set (:message/order baseline))))
    (is (= ["s1-m0" "s1-m1" "s1-m7" "s1-m8" "s1-m9"]
           (:message/order baseline))
        "the union keeps conversation order")))

(deftest an-explicit-inactive-window-publishes-that-tail
  (let [baseline (baseline-with-owners {:inactive-history-window-size 2})]
    (is (= #{"s1-m7" "s1-m8" "s1-m9" "s2-m8" "s2-m9"}
           (published-ids baseline))
        "an inactive window publishes that session's newest entries")
    (is (= (published-ids baseline)
           (set (:message/order baseline)))
        "order matches the published table")))

(deftest a-nil-window-publishes-everything
  (let [baseline (baseline-with-owners {:history-window-size nil
                                        :inactive-history-window-size nil})]
    (is (= 20 (count (published-ids baseline)))
        "an explicit nil keeps the pre-windowing behaviour")
    (is (= (published-ids baseline)
           (set (:message/order baseline))))))

(deftest a-cached-projection-still-honours-the-window
  (let [cache (atom {})
        narrow (baseline-with-owners {:session-cache cache})
        wide (baseline-with-owners {:session-cache cache
                                    :history-window-size nil
                                    :inactive-history-window-size nil})]
    (is (= #{"s1-m7" "s1-m8" "s1-m9"} (published-ids narrow))
        "the cached projection is narrowed on every publish")
    (is (= 20 (count (published-ids wide)))
        "a wider window reuses the cached projection and republishes more")))

(deftest a-client-without-published-windows-still-gets-the-tail
  (testing "`published-windows` may be absent, which is the fresh-client case"
    (let [baseline (snapshot/baseline
                    {:sessions [(a-session "s1") (a-session "s2")]
                     :owner-snapshots {"s1" (owner-snapshot "s1" 10)
                                       "s2" (owner-snapshot "s2" 10)}
                     :active-session "s1"
                     :history-window-size 3})]
      (is (= #{"s1-m7" "s1-m8" "s1-m9"} (published-ids baseline))
          "no `:published-windows` atom means nothing was fetched yet")
      (is (= (published-ids baseline)
             (set (:message/order baseline)))))))

(deftest a-windowed-entry-keeps-the-id-it-has-without-a-window
  ;; A conversation entry with no explicit id gets `message-N` from its
  ;; position in the FULL vector.  Narrowing must not renumber it: a client
  ;; that saw `message-9` must keep seeing `message-9`, or every tick would
  ;; churn identities and the fetched-window union would stop matching.
  (let [anonymous (mapv (fn [i]
                          {:role (if (even? i) :user :assistant)
                           :text (str "entry " i)})
                        (range 10))
        baseline (snapshot/baseline
                  {:sessions [(a-session "s1")]
                   :owner-snapshots {"s1" {:version 1
                                           :state {:conversation anonymous
                                                   :runs {}}}}
                   :active-session "s1"
                   :history-window-size 3})]
    (is (= ["message-7" "message-8" "message-9"]
           (:message/order baseline))
        "the fallback keeps the position from the full conversation")
    (is (= (set (:message/order baseline))
           (published-ids baseline)))
    (is (= "entry 9" (get-in baseline [:message/by-id "message-9" :text]))
        "the id still names the same entry"))))

(deftest the-histories-path-alone-honours-the-window
  (testing "without owner snapshots the window works, which is why the defect hid"
    (let [baseline (snapshot/baseline
                    {:sessions [(a-session "s1") (a-session "s2")]
                     :session-records [{:session-id "s1" :revision 1}
                                       {:session-id "s2" :revision 1}]
                     :owner-snapshots {}
                     :active-session "s1"
                     :published-windows (atom {})
                     :history-window-size 3})]
      (is (empty? (:message/by-id baseline))
          "an empty owner set leaves nothing to publish here; the windowed
          assertion for a real record lives in lazy_owner_test:203"))))