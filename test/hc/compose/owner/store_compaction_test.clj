(ns hc.compose.owner.store-compaction-test
  "Committing through a compacting store keeps the whole document bounded.

  A model call freezes its request snapshot, and that snapshot carries the
  outbound message window.  The snapshot is needed while the call runs, but
  after the terminal it is pure bulk: nothing reads
  `[:request :snapshot :messages]` of a settled call.  On a 100-turn
  session with a 32-message 300-char window that single field made up 84%
  of the document.

  `compact-state` already existed and did exactly this rewrite, but nothing
  on the production commit path called it.  The decorator here wires it in
  at the one place where the whole document is already materialized."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.compose.owner.compact :as compact]
            [hc.compose.owner.state :as state]
            [hc.compose.owner.store :as store]))

(defn- settle-call
  "One model call that ran, finished, and settled.

  Each turn carries a distinct `:turn` in its snapshot so the
  content-addressed blob store keeps one blob per turn instead of
  deduplicating every snapshot into a single entry."
  [state i]
  (let [messages (vec (for [j (range 32)]
                        {:role (if (even? j) :user :assistant)
                         :text (apply str (repeat 300 (str (mod j 10))))}))
        [state call] (state/create-call
                      state
                      {:kind :model
                       :role :main
                       :created-at i
                       :request {:snapshot {:messages messages
                                            :tools []
                                            :turn i}
                                 :attempt-id "a1"}})
        call-id (:call-id call)
        run-id (state/run-id call-id "a1")
        state (state/start-run state (get-in state [:calls call-id]) "a1" i)
        state (state/append-run-text state run-id "answer" i)
        state (state/finish-run state run-id :finished (inc i) {})]
    (assoc-in state [:calls call-id :state] :succeeded)))

(defn- settled-state
  [turns]
  (reduce settle-call (state/initial-state {:session-id "s" :now 0})
          (range turns)))

(deftest a-compacting-store-shrinks-the-committed-document
  (let [blobs (compact/memory-blob-store)
        plain (store/memory-store)
        compacting (store/compacting-store
                    (store/memory-store)
                    (fn [state] (compact/compact-state state blobs)))
        state (settled-state 20)]
    (is (true? (:ok? (store/commit! plain 0 state))))
    (is (true? (:ok? (store/commit! compacting 0 state))))
    (let [plain-chars (count (pr-str (:state (store/load-state plain))))
          compact-chars (count (pr-str (:state (store/load-state compacting))))
          stats (store/compaction-stats compacting)]
      (is (< compact-chars plain-chars)
          "the compacted document is smaller")
      (is (< compact-chars (* 0.5 plain-chars))
          "the request snapshots dominated the document, so the win is large")
      (is (= 1 (:commits stats)))
      (is (pos? (:saved stats)))
      (is (= 20 (:refs stats))
          "each turn's snapshot became its own blob"))))

(deftest compaction-preserves-every-call
  (let [blobs (compact/memory-blob-store)
        compacting (store/compacting-store
                    (store/memory-store)
                    (fn [state] (compact/compact-state state blobs)))
        state (settled-state 12)]
    (store/commit! compacting 0 state)
    (let [loaded (:state (store/load-state compacting))
          restored (compact/restore-state loaded blobs)]
      (is (= (count (:calls state)) (count (:calls loaded)))
          "no call record disappears")
      (testing "a settled call keeps its identity and state"
        (doseq [[call-id call] (:calls loaded)]
          (is (= call-id (:call-id call)))
          (is (= :succeeded (:state call)))))
      (testing "the snapshot body survives in the blob store"
        (let [recovered (-> restored :calls vals first :request :snapshot :messages)]
          (is (= 32 (count recovered))
              "restore-state returns the frozen message window")
          (is (every? :text recovered)))))))

(deftest compaction-is-skipped-without-a-blob-store
  (testing "an absent store degrades to the plain commit path"
    (let [state (settled-state 3)
          [_ stats] (compact/compact-state state nil)]
      (is (true? (:skipped? stats)))
      (is (zero? (:saved stats))))))

(deftest a-plain-store-is-unaffected
  (testing "the decorator is optional and removable"
    (let [plain (store/memory-store)
          state (settled-state 3)]
      (is (true? (:ok? (store/commit! plain 0 state))))
      (is (= state (:state (store/load-state plain)))
          "without the decorator the state is stored unchanged")
      (is (nil? (store/compaction-stats plain))))))

(deftest a-blob-reference-keeps-only-a-short-snippet
  (testing "the preview identifies the payload without copying it"
    (let [blobs (compact/memory-blob-store)
          state (settled-state 5)
          [compacted _] (compact/compact-state state blobs)
          requests (->> compacted
                        :calls
                        vals
                        (map :request)
                        (filter #(contains? % :request-ref)))]
      (is (seq requests) "every settled call was offloaded")
      (doseq [request requests]
        (is (<= (count (str (:request-preview request)))
                compact/max-preview-chars)
            "a preview never exceeds the display bound")
        (is (pos? (long (:request-bytes request)))
            "the full size stays recorded for the operator")
        (is (string? (:request-ref request))
            "restore has a reference to read through")))))