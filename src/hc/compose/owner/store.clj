(ns hc.compose.owner.store
  "Committed state storage for one Owner.

  The Owner is the only writer.  A store performs one atomic
  compare-and-set from an expected version to a new version.  It never runs
  inside a Missionary cancellation domain: an interrupted executor cannot
  abort a commit.

  A store returns:

    (load-state store)            -> {:version n :state map|nil}
    (commit! store expected new)  -> {:ok? true :version n}
                                   | {:ok? false :error {:code .. :message ..}}
    (close! store)                -> any

  `memory-store` is the reference implementation for tests and ephemeral
  sessions.  A real store must keep the same compare-and-set contract."
  (:require [clojure.string :as str]))

(defprotocol Store
  (load-state [store]
    "Return the last committed version and state.  An empty store returns
    `{:version 0 :state nil}`.")
  (commit! [store expected-version new-state]
    "Commit NEW-STATE only when the stored version equals EXPECTED-VERSION.")
  (close! [store]
    "Release store resources.  This operation is idempotent."))

(defn valid-state?
  "True when STATE is a map with a non-blank string session id."
  [state]
  (and (map? state)
       (string? (:session-id state))
       (not (str/blank? (:session-id state)))))

(defn invalid-state-error
  [state]
  {:code :INVALID_STATE
   :message "committed state must be a map with a non-blank :session-id"
   :details {:class (some-> state class .getName)}})

(defn- closed-error
  []
  {:code :STORE_CLOSED
   :message "store is closed"})

(defn- conflict-error
  [expected actual]
  {:code :VERSION_CONFLICT
   :message "stored version does not match the expected version"
   :details {:expected expected :actual actual}})

(defrecord MemoryStore [cell closed?]
  Store
  (load-state [_]
    (let [{:keys [version state]} @cell]
      {:version version :state state}))

  (commit! [_ expected-version new-state]
    (if @closed?
      {:ok? false :error (closed-error)}
      (if-not (valid-state? new-state)
        {:ok? false :error (invalid-state-error new-state)}
        (let [[before after]
              (swap-vals! cell
                          (fn [{:keys [version] :as snapshot}]
                            (if (= version expected-version)
                              {:version (inc (long version)) :state new-state}
                              snapshot)))
              actual (:version before)]
          (if (= actual expected-version)
            {:ok? true :version (:version after)}
            {:ok? false :error (conflict-error expected-version actual)})))))

  (close! [_]
    (reset! closed? true)
    {:ok? true :closed? true}))

(defn memory-store
  "Create an in-memory store.

  Options:

    :initial-state    map or nil
    :initial-version  non-negative integer, default 0

  An initial state without a matching version is a wiring error.  The
  caller must commit it explicitly."
  ([] (memory-store {}))
  ([{:keys [initial-state initial-version]
     :or {initial-version 0}}]
   (when-not (and (integer? initial-version) (not (neg? initial-version)))
     (throw (ex-info "initial-version must be a non-negative integer"
                     {:code :INVALID_VERSION :version initial-version})))
   (when (and (some? initial-state) (not (valid-state? initial-state)))
     (throw (ex-info "initial-state is invalid"
                     {:code :INVALID_STATE
                      :error (invalid-state-error initial-state)})))
   (when (and (some? initial-state) (zero? initial-version))
     (throw (ex-info "an initial state needs a non-zero initial version"
                     {:code :INVALID_VERSION
                      :initial-version initial-version})))
  (->MemoryStore (atom {:version initial-version :state initial-state})
                 (atom false))))

(defrecord CompactingStore [inner compact-fn stats]
  Store
  (load-state [_]
    (load-state inner))

  (commit! [_ expected-version new-state]
    ;; Compaction runs on the commit path because that is the only place
    ;; the whole document is already materialized.  It rewrites large
    ;; terminal-call content to content-addressed references, so the bytes
    ;; reaching storage are bounded by the number of live records rather
    ;; than by how much the session has produced.
    (let [[compacted result] (compact-fn new-state)]
      (when (and (map? result) (pos? (long (or (:saved result) 0))))
        (swap! stats (fn [s]
                       (-> s
                           (update :commits (fnil inc 0))
                           (update :saved (fnil + 0) (long (:saved result)))
                           (update :refs (fnil max 0)
                                   (long (or (:refs result) 0)))))))
      (commit! inner expected-version compacted)))

  (close! [_]
    (close! inner)))

(defn compacting-store
  "Wrap STORE so every commit first shrinks large terminal-call content.

  COMPACT-FN is `(fn [state] [state' stats])`, normally
  `hc.compose.owner.compact/compact-state` partially applied to a blob
  store.  The decorator is optional and removable: a plain store keeps the
  same compare-and-set contract, it just stores a larger document.

  `stats` accumulates `{:commits :saved :refs}` so the operator can see how
  much the session has folded and how many distinct blobs it references."
  ([store compact-fn] (compacting-store store compact-fn (atom {})))
  ([store compact-fn stats]
   (when-not (ifn? compact-fn)
     (throw (ex-info "compacting-store needs a compact-fn"
                     {:code :INVALID_COMPACT_FN})))
   (->CompactingStore store compact-fn stats)))

(defn compaction-stats
  [store]
  (when (instance? CompactingStore store)
    @(:stats store)))
