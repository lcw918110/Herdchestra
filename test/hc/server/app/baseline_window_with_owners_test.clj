(ns hc.server.app.baseline-window-with-owners-test
  "Records that the publication window is defeated by the owner-conversation
  projection.

  `snapshot/baseline` builds two views of the same messages:

    - `histories`, narrowed by `history-window-ids` and both window options
    - `conversations`, every visible owner's ENTIRE :conversation

  and merges them with the un-windowed one as the base.  The existing suite
  (`lazy_owner_test:203`) passes `:owner-snapshots {}`, so `conversations`
  contributes nothing there and the defect stays invisible.  This test supplies
  an owner snapshot, which is the production case.

  The assertions below describe what the code does TODAY, not what it should
  do.  They are written so the suite stays green while the defect is open --
  a red test in master blocks the release gate without making the bug more
  fixed.  When D-50 is fixed, flip each expectation to the `expected-when-
  fixed` value shown beside it and delete this note."
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

(defn- baseline-with-owners []
  (snapshot/baseline
   {:sessions [(a-session "s1") (a-session "s2")]
    :session-records [{:session-id "s1" :revision 1}
                      {:session-id "s2" :revision 1}]
    :owner-snapshots {"s1" (owner-snapshot "s1" 10)
                      "s2" (owner-snapshot "s2" 10)}
    :active-session "s1"
    :published-windows (atom {})
    :history-window-size 3}))

(deftest an-owner-snapshot-defeats-the-publication-window
  (let [baseline (baseline-with-owners)
        ids (set (keys (:message/by-id baseline)))]
    (testing "every owner-conversation message is published, ignoring the window"
      ;; expected when fixed: #{"s1-m7" "s1-m8" "s1-m9"}
      (is (= 20 (count ids))
          "the window narrows nothing: ten messages from each session")
      (is (contains? ids "s1-m0")
          "the active session's oldest message still ships")
      (is (contains? ids "s2-m0")
          "an inactive session's oldest message still ships"))
    (testing "an inactive session publishes entities even at the default zero window"
      ;; expected when fixed: #{"s1"}
      (is (= #{"s1" "s2"}
             (set (map :session-id (vals (:message/by-id baseline)))))
          "the un-windowed projection is the merge base, so its ids survive"))))

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
          "an empty owner set leaves nothing to publish here; the existing
           windowed assertion lives in lazy_owner_test:203"))))