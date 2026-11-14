(ns hc.server.app.session-command-index-test
  "The creation-command index must commit and delete with the session record
  in the same storage partition, survive a store reopen, include the command
  epoch in its identity, and refuse a second session for the same identity."
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.app.session-persistence :as session-persistence]
            [hc.server.sessions :as sessions]
            [hc.server.sessions.ports :as ports]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as storage-support]
            [hc.server.storage.view :as view]))

(defn- creation-identity
  [epoch]
  {:scope "app" :command-epoch epoch :command-id "cmd-1"})

(defn- new-session
  [session-id owner-id command-identity now]
  (:session (sessions/new-session
             {:session-id session-id
              :owner-id owner-id
              :creation-command command-identity
              :now now})))

(deftest creation-command-index-is-epoch-scoped-and-unique
  (let [dir (storage-support/temp-dir "hc-session-command-")]
    (try
      (let [store (storage/open-store {:path dir :create? true})]
        (try
          (let [p1 (session-persistence/persistence store)
                p2 (session-persistence/persistence store)
                epoch-1 (creation-identity 1)
                epoch-2 (creation-identity 2)
                reuse-identity {:scope "app"
                                :command-epoch 3
                                :command-id "cmd-reuse"}
                s1 (new-session "s-1" "o-1" epoch-1 1000)
                committed (sessions/commit-session! p1 s1)]
            (is (= :committed (:status committed)))
            (is (= epoch-1 (:creation-command s1)))
            (testing "the same command-id in another epoch is not the same command"
              (is (= :found (:status (ports/lookup-session-by-command
                                      p1 epoch-1))))
              (is (= :missing (:status (ports/lookup-session-by-command
                                        p1 epoch-2)))))
            (testing "a reuse claim persists identity -> existing session"
              (is (= :claimed
                     (:status (ports/claim-session-command!
                               p1 reuse-identity "s-1"))))
              (is (= :unchanged
                     (:status (ports/claim-session-command!
                               p2 reuse-identity "s-1"))))
              (is (= "s-1"
                     (:session-id
                      (:session (ports/lookup-session-by-command
                                 p1 reuse-identity))))))
            (testing "a second session for the same identity is refused at commit"
              (let [s-b (new-session "s-b" "o-b" epoch-1 2000)
                    duplicate (sessions/commit-session! p2 s-b)]
                (is (= :duplicate (:status duplicate)))
                (is (= "s-1" (:session-id (:session duplicate))))
                (is (= :missing (:status (ports/read-session p2 "s-b"))))
                (is (= "s-1" (:session-id
                              (:session (ports/lookup-session-by-command
                                         p2 epoch-1)))))))
            (testing "a new epoch may reuse the command-id for a new session"
              (let [s2 (new-session "s-2" "o-2" epoch-2 3000)
                    committed-2 (sessions/commit-session! p2 s2)]
                (is (= :committed (:status committed-2)))
                (is (= "s-1" (:session-id
                              (:session (ports/lookup-session-by-command
                                         p2 epoch-1)))))
                (is (= "s-2" (:session-id
                              (:session (ports/lookup-session-by-command
                                         p2 epoch-2)))))
                (is (= :duplicate
                       (:status (ports/claim-session-command!
                                 p2 reuse-identity "s-2"))))
                (is (= "s-1"
                       (:session-id
                        (:session (ports/lookup-session-by-command
                                   p2 reuse-identity))))))))
          (finally
            (storage/close-store! store))))
      (let [store (storage/open-store {:path dir})]
        (try
          (let [persistence (session-persistence/persistence store)
                found-1 (ports/lookup-session-by-command
                         persistence (creation-identity 1))
                found-2 (ports/lookup-session-by-command
                         persistence (creation-identity 2))
                found-reuse (ports/lookup-session-by-command
                             persistence {:scope "app"
                                          :command-epoch 3
                                          :command-id "cmd-reuse"})]
            (is (= :found (:status found-1)))
            (is (= "s-1" (:session-id (:session found-1))))
            (is (= :found (:status found-2)))
            (is (= "s-2" (:session-id (:session found-2))))
            (is (= :found (:status found-reuse)))
            (is (= "s-1" (:session-id (:session found-reuse))))
            (let [stored (ports/read-session persistence "s-1")
                  revision (:revision (:session stored))
                  deleted (ports/delete-session! persistence revision "s-1")]
              (is (= :deleted (:status deleted)))
              (is (= :missing
                     (:status (ports/lookup-session-by-command
                               persistence (creation-identity 1)))))
              (is (= :missing
                     (:status (ports/lookup-session-by-command
                               persistence {:scope "app"
                                            :command-epoch 3
                                            :command-id "cmd-reuse"}))))
              (is (= :found
                     (:status (ports/lookup-session-by-command
                               persistence (creation-identity 2)))))))
          (finally
            (storage/close-store! store))))
      (finally
        (storage-support/delete-tree! dir)))))

(def ^:private shared-blob-id
  "Canonical content ID; the blob store rejects test shortcuts such as
  `sha256-shared`, so the reference-count assertions must use the real form."
  "sha256-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(defn- sample-session
  [session-id owner-id title]
  (let [attachment {:attachment-id "att-1"
                    :blob-id shared-blob-id
                    :size 3}
        message {:message-id "m-1"
                 :session-id session-id
                 :role :user
                 :current-version "m-1-v1"
                 :versions {"m-1-v1" {:content "hello"
                                       :attachments [attachment]}}
                 :attachments [attachment]}]
    {:session-id session-id
     :owner-id owner-id
     :revision 0
     :status :active
     :language :en
     :title title
     :created-at 1
     :updated-at 1
     :messages {"m-1" message}
     :message-order ["m-1"]
     :drafts {"client" {:text "draft" :attachments [] :citations []}}
     :visible-message-count 1}))

(defn- ref-ids
  [store]
  (->> (storage/manage-blob-refs! store {:partition "sessions" :op :list})
       :refs
       (map :id)
       set))

(deftest summary-and-blob-counts-are-derived-point-documents-test
  (let [dir (storage-support/temp-dir "hc-session-summary-")]
    (try
      (let [store (storage/open-store {:path dir :create? true})]
        (try
          (let [p (session-persistence/persistence store)
                s1 (sample-session "s-1" "o-1" "one")
                s2 (sample-session "s-2" "o-2" "two")]
            (is (= :committed (:status (ports/commit-session! p 0 s1))))
            (testing "the summary document carries no message bodies"
              (let [summary-doc (storage/read-document
                                 store
                                 {:partition "sessions"
                                  :session-id nil
                                  :kind :session-summary
                                  :id "s-1"})
                    summary (-> (ports/read-session-summary p "s-1")
                                :session)]
                (is (some? summary-doc))
                (is (not (contains? summary-doc :messages)))
                (is (not (contains? summary-doc :message-order)))
                (is (= :found (:status (ports/read-session-summary p "s-1"))))
                (is (not (contains? summary :messages)))
                (is (= 1 (:visible-message-count summary)))
                (is (true? (:has-draft? summary)))
                (is (= "hello"
                       (get-in summary [:last-visible-message :text])))))
            (testing "the reference count index follows one session"
              (is (= {shared-blob-id 1}
                     (:counts (storage/read-document
                               store
                               {:partition "sessions"
                                :session-id nil
                                :kind :session-ref-index
                                :id "sessions"})))))
            (testing "a second owner raises the count without a second reference"
              (is (= :committed (:status (ports/commit-session! p 0 s2))))
              (is (= {shared-blob-id 2}
                     (:counts (storage/read-document
                               store
                               {:partition "sessions"
                                :session-id nil
                                :kind :session-ref-index
                                :id "sessions"})))))
            (testing "deleting one owner keeps the shared reference"
              (let [stored (-> (ports/read-session p "s-1") :session)]
                (is (= :deleted
                       (:status (ports/delete-session! p
                                                       (:revision stored)
                                                       "s-1")))))
              (is (= #{shared-blob-id} (ref-ids store)))
              (is (nil? (storage/read-document
                         store
                         {:partition "sessions"
                          :session-id nil
                          :kind :session-summary
                          :id "s-1"})))
              (is (= ["s-2"] (:ids (ports/list-session-ids p)))))
            (testing "deleting the last owner releases the reference"
              (let [stored (-> (ports/read-session p "s-2") :session)]
                (is (= :deleted
                       (:status (ports/delete-session! p
                                                       (:revision stored)
                                                       "s-2")))))
              (is (empty? (ref-ids store)))
              (is (= {:counts {}}
                     (select-keys (storage/read-document
                                   store
                                   {:partition "sessions"
                                    :session-id nil
                                    :kind :session-ref-index
                                    :id "sessions"})
                                  [:counts])))))
          (finally
            (storage/close-store! store))))
      (finally
        (storage-support/delete-tree! dir)))))

(deftest summary-reads-fall-back-to-one-record-point-read-test
  (let [dir (storage-support/temp-dir "hc-session-summary-fallback-")]
    (try
      (let [store (storage/open-store {:path dir :create? true})]
        (try
          (storage/commit-migration!
           store
           {:migration-id "legacy-session"
            :partition "sessions"
            :put [{:kind :session-record
                   :id "legacy-1"
                   :doc {:session-id "legacy-1"
                         :revision 0
                         :status :active
                         :messages {"m-1" {:message-id "m-1"
                                           :deleted? false}}
                         :message-order ["m-1"]}}]})
          (let [p (session-persistence/persistence store)
                result (ports/read-session-summary p "legacy-1")]
            (is (= :found (:status result)))
            (is (= 1 (:visible-message-count (:session result))))
            (is (not (contains? (:session result) :messages)))
            (is (not (contains? (:session result) :message-order)))
            (is (= ["legacy-1"] (:ids (ports/list-session-ids p)))))
          (finally
            (storage/close-store! store))))
      (finally
        (storage-support/delete-tree! dir)))))

(deftest persistence-notifies-advisory-history-hooks-test
  (storage-support/with-temp-store [store {}]
    (let [seen (atom [])
          index (reify ports/SessionHistoryIndex
                  (session-committed! [_ session]
                    (swap! seen conj [:protocol-commit (:session-id session)]))
                  (session-deleted! [_ session-id]
                    (swap! seen conj [:protocol-delete session-id])))
          p (session-persistence/persistence
             store
             {:on-session-committed
              (fn [session]
                (swap! seen conj [:commit (:session-id session)]))
              :on-session-deleted
              (fn [session-id]
                (swap! seen conj [:delete session-id]))
              :history-index index})
          s (sample-session "s-hook" "o-hook" "hook")]
      (is (= :committed (:status (ports/commit-session! p 0 s))))
      (is (= :deleted (:status (ports/delete-session! p 0 "s-hook"))))
      (is (= [[:commit "s-hook"] [:delete "s-hook"]] @seen))
      (testing "the protocol object is used when a callback is absent"
        (reset! seen [])
        (let [protocol-only
              (session-persistence/persistence store
                                               {:history-index index})
              again (sample-session "s-hook-2" "o-hook-2" "hook two")]
          (is (= :committed (:status (ports/commit-session! protocol-only
                                                           0 again))))
          (is (= :deleted
                 (:status (ports/delete-session! protocol-only
                                                 0 "s-hook-2"))))
          (is (= [[:protocol-commit "s-hook-2"]
                  [:protocol-delete "s-hook-2"]]
                 @seen)))))))

(deftest steady-state-session-paths-never-scan-the-partition-view-test
  (storage-support/with-temp-store [store {}]
    (let [p (session-persistence/persistence store)
          s1 (sample-session "s-point-1" "o-point-1" "one")
          s2 (sample-session "s-point-2" "o-point-2" "two")]
      (is (= :committed (:status (ports/commit-session! p 0 s1))))
      (let [views (atom [])]
        (with-redefs [storage/read-session-view
                      (fn [& args]
                        (swap! views conj args)
                        (throw (ex-info "steady state must use point reads"
                                        {:args args})))]
          (is (= :committed (:status (ports/commit-session! p 0 s2))))
          (is (= :found (:status (ports/read-session-summary p "s-point-2"))))
          (is (= :ok (:status (ports/list-session-ids p))))
          (is (= :deleted
                 (:status (ports/delete-session! p 0 "s-point-1")))))
        (is (empty? @views)
            "no commit, summary, list or delete may read the full partition view")))))

(deftest app-config-round-trip-uses-point-reads-test
  (storage-support/with-temp-store [store {}]
    (let [p (session-persistence/persistence store)
          views (atom [])]
      (with-redefs [view/session-view
                    (fn [& args]
                      (swap! views conj args)
                      (throw (ex-info "app config must use point reads"
                                      {:args args})))]
        (is (= :missing (:status (ports/read-config p))))
        (is (= :committed
               (:status (ports/commit-config!
                         p 0 {:connections {} :assistants {}}))))
        (let [read (ports/read-config p)]
          (is (= :found (:status read)))
          (is (= 1 (get-in read [:config :revision])))
          (is (= {} (get-in read [:config :connections]))))
        (is (= :conflict
               (:status (ports/commit-config!
                         p 0 {:connections {} :assistants {}}))))
        (is (= :committed
               (:status (ports/commit-config!
                         p 1 {:connections {} :assistants {}}))))
        (is (= 2 (:revision (:config (ports/read-config p))))))
      (is (empty? @views)
          "config reads and writes must not build a partition view")
      (is (= 2
             (:revision
              (storage/read-document
               store
               {:partition "config"
                :session-id nil
                :kind :app-config
                :id "config"})))))))

;; ---------------------------------------------------------------------------
;; Committed session count

(defn- count-doc
  [store]
  (storage/read-document store
                         {:partition "sessions"
                          :session-id nil
                          :kind :session-count
                          :id "sessions"}))

(deftest committed-session-count-is-constant-time-and-moves-with-the-index-test
  (storage-support/with-temp-store [store {}]
    (let [p (session-persistence/persistence store)]
      (is (= 0 (:count (ports/committed-session-count p)))
          "an empty store reports zero rather than unknown")
      (is (nil? (count-doc store))
          "an untouched store has no committed count yet")
      (is (= 0 (:count (ports/committed-session-count p)))
          "the adapter derives zero from the empty index instead of guessing")
      (doseq [index (range 5)]
        (let [session-id (str "s-count-" index)]
          (is (= :committed
                 (:status (ports/commit-session! p 0
                                                 (sample-session
                                                  session-id
                                                  (str "o-count-" index)
                                                  session-id)))))))
      (is (= 5 (:count (ports/committed-session-count p))))
      (is (= 5 (:count (count-doc store))))
      (is (= 5 (count (:ids (ports/list-session-ids p))))
          "the counter and the index agree")
      (testing "an update does not change the count"
        (let [stored (:session (ports/read-session p "s-count-0"))]
          (is (= :committed
                 (:status (ports/commit-session!
                           p (:revision stored)
                           (assoc stored :revision (inc (:revision stored))
                                  :title "renamed"))))))
        (is (= 5 (:count (ports/committed-session-count p)))))
      (testing "a delete decrements in the same commit as the index"
        (let [deleted (ports/delete-session! p 0 "s-count-1")]
          (is (= :deleted (:status deleted)))
          (is (= 4 (:count (ports/committed-session-count p))))
          (is (= 4 (:count (count-doc store))))
          (is (= 4 (count (:ids (ports/list-session-ids p)))))))
      (testing "a session re-committed after a delete is counted once"
        (is (= :committed
               (:status (ports/commit-session! p 0
                                               (sample-session
                                                "s-count-1" "o-count-1"
                                                "s-count-1")))))
        (is (= 5 (:count (ports/committed-session-count p))))
        (is (= 5 (count (:ids (ports/list-session-ids p)))))))))

(deftest committed-session-count-stays-correct-at-a-thousand-sessions-test
  (storage-support/with-temp-store [store {}]
    (let [p (session-persistence/persistence store)
          ids (mapv #(str "scale-count-" %) (range 1000))]
      (doseq [[batch-index batch] (map-indexed vector (partition-all 100 ids))]
        (let [migration-id (str "count-scale-" batch-index)
              puts (mapv (fn [session-id]
                           {:kind :session-record
                            :id session-id
                            :doc (sample-session session-id
                                                 (str session-id "-owner")
                                                 session-id)})
                         batch)
              all-ids (vec (take (* (inc batch-index) 100) ids))]
          (is (:ok? (storage/commit-migration!
                     store
                     {:migration-id migration-id
                      :partition "sessions"
                      :lightweight-return? true
                      :put (conj puts
                                 {:kind :session-index
                                  :id "sessions"
                                  :doc {:id "sessions" :ids all-ids}}
                                 {:kind :session-count
                                  :id "sessions"
                                  :doc {:id "sessions" :count (count all-ids)}})})))))
      (is (= 1000 (:count (ports/committed-session-count p))))
      (is (= 1000 (:count (count-doc store))))
      (is (= 1000 (count (:ids (ports/list-session-ids p))))
          "the maintained count equals the index length at 1000 sessions")
      (testing "the count is a point read, not a scan"
        (let [reads (atom [])
              orig storage/read-document]
          (with-redefs [storage/read-document
                        (fn [target opts]
                          (swap! reads conj opts)
                          (orig target opts))]
            (is (= 1000 (:count (ports/committed-session-count p)))))
          (is (= [:session-count] (mapv :kind @reads))
              "the adapter reads one count document and nothing else"))))))

(deftest an-index-without-a-count-document-falls-back-to-the-index-length-test
  (storage-support/with-temp-store [store {}]
    (let [p (session-persistence/persistence store)]
      ;; A store written before the count document existed (or restored from a
      ;; package that dropped it) carries only the index.
      (is (:ok? (storage/commit-migration!
                 store
                 {:migration-id "legacy-index"
                  :partition "sessions"
                  :lightweight-return? true
                  :put [{:kind :session-record :id "legacy-1"
                         :doc (sample-session "legacy-1" "o-legacy" "legacy")}
                        {:kind :session-record :id "legacy-2"
                         :doc (sample-session "legacy-2" "o-legacy" "legacy")}
                        {:kind :session-index
                         :id "sessions"
                         :doc {:id "sessions" :ids ["legacy-1" "legacy-2"]}}]})))
      (is (nil? (count-doc store)))
      (is (= {:status :ok :count 2}
             (ports/committed-session-count p))
          "a missing count falls back to the index length, never to unknown")
      (testing "the next commit writes the count again"
        (is (= :committed
               (:status (ports/commit-session! p 0
                                               (sample-session
                                                "legacy-3" "o-legacy-3"
                                                "legacy")))))
        (is (= 3 (:count (count-doc store))))
        (is (= 3 (:count (ports/committed-session-count p))))))))

(deftest a-failed-count-read-is-unknown-never-zero-test
  (storage-support/with-temp-store [store {}]
    (let [p (session-persistence/persistence store)]
      (is (= :committed
             (:status (ports/commit-session! p 0 (sample-session
                                                  "s-count-boom" "o-count-boom"
                                                  "boom")))))
      (with-redefs [storage/read-document
                    (fn [& _] (throw (ex-info "count unavailable" {})))]
        (let [result (ports/committed-session-count p)]
          (is (= :unknown (:status result)))
          (is (not= 0 (:count result))))))))

(deftest a-persistence-without-the-count-protocol-still-counts-test
  (let [store (ports/memory-persistence)]
    (is (= :committed
           (:status (ports/commit-session! store 0
                                           {:session-id "m-1" :revision 0}))))
    (is (= {:status :ok :count 1} (ports/committed-session-count store)))
    (is (= :deleted (:status (ports/delete-session! store 0 "m-1"))))
    (is (= {:status :ok :count 0} (ports/committed-session-count store))))
  (testing "an adapter without SessionCountRead falls back to the id list"
    (let [store (reify ports/SessionPersistence
                  (read-session [_ _] {:status :missing})
                  (commit-session! [_ _ _] {:status :committed :revision 0})
                  (list-session-ids [_] {:status :ok :ids ["a" "b" "c"]})
                  (delete-session! [_ _ _] {:status :missing})
                  (read-config [_] {:status :missing})
                  (commit-config! [_ _ _] {:status :committed :revision 0}))]
      (is (= {:status :ok :count 3} (ports/committed-session-count store)))))
  (testing "a failing fallback list is unknown, never zero"
    (let [store (reify ports/SessionPersistence
                  (read-session [_ _] {:status :missing})
                  (commit-session! [_ _ _] {:status :committed :revision 0})
                  (list-session-ids [_] {:status :error
                                         :error {:code :INDEX_UNAVAILABLE}})
                  (delete-session! [_ _ _] {:status :missing})
                  (read-config [_] {:status :missing})
                  (commit-config! [_ _ _] {:status :committed :revision 0}))]
      (is (= :unknown (:status (ports/committed-session-count store)))))))

(deftest a-restore-rewrites-the-count-from-the-restored-index-test
  (let [dir (storage-support/temp-dir "hc-count-restore-")]
    (try
      (let [source (storage/open-store {:path dir :create? true})]
        (try
          (let [p (session-persistence/persistence source)]
            (doseq [index (range 3)]
              (is (= :committed
                     (:status (ports/commit-session! p 0
                                                     (sample-session
                                                      (str "rs-" index)
                                                      (str "rs-o-" index)
                                                      "restore"))))))
            (let [package (storage/export-state source {})
                  restored (storage/restore-state! source package
                                                   {:migration-id
                                                    "restore-count-check"
                                                    :quiescent? true})]
              (is (:ok? restored))
              (is (= 3 (:count (count-doc source)))
                  "a restore rewrites the count from the restored index")
              (is (= 3 (:count (ports/committed-session-count p)))
                  "the adapter still agrees with the index after a restore")))
          (finally
            (storage/close-store! source))))
      (finally
        (storage-support/delete-tree! dir)))))
