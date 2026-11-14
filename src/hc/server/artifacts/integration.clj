(ns hc.server.artifacts.integration
  "Application assembly for the artifact service.

  The application owns the store and the blob root; this adapter builds the
  service once and exposes the small surface the Owner and snapshot builder
  need.  It does not start a gateway route and does not publish events: the
  caller sends `:announcement` through the existing `CUSTOM` channel.

  The adapter also keeps a small content-ID -> media-type cache.  The gateway
  accepts `:content-type-fn` but has no database access, so this is how an
  artifact's type reaches the independent blob route without teaching the
  gateway about artifact metadata.  The cache is filled by registration and
  by snapshot/list reads; a restart refills it as sessions load."
  (:require [hc.server.artifacts :as artifacts]))

(defn- remember-types!
  [cache artifacts]
  (swap! cache merge
         (into {}
               (keep (fn [artifact]
                       (when (and (:content-id artifact)
                                  (:media-type artifact))
                         [(:content-id artifact) (:media-type artifact)])))
               artifacts))
  cache)

(def ^:private announcement-accepted-codes
  "Publish outcomes that mean the event actually reached the channel."
  #{:ok :accepted :delivered})

(defn announcement-accepted?
  "True only when a publisher explicitly reports acceptance.

  `nil` (a callback that returns nothing), `false`, `{:ok? false ...}` and
  `{:delivered 0 ...}` all mean the user may never receive the event.
  Treating nil as success persisted `:announced? true` on an artifact nobody
  was told about, and no retry could discover the loss.  A truthy non-map
  value stays accepted: a publisher that returns its own token (an id, a
  count) already signalled that the hand-off happened."
  [published]
  (cond
    (nil? published) false
    (false? published) false
    (map? published)
    (and (not (false? (:ok? published)))
         (or (true? (:ok? published))
             (contains? announcement-accepted-codes
                        (some-> (:status published) keyword))
             (pos? (long (or (:delivered published) 0)))))
    :else true))

(defn- announcement-failure
  [code message data]
  (ex-info message
           (merge {:code code
                   :artifacts/expected? true}
                  data)))

(defn register-and-announce!
  "Register one artifact, publish its announcement, then persist
  `:announced? true`.

  Acceptance is explicit: only a publisher that reports a truthy result
  (`true`, `{:ok? true ...}` or `{:delivered n>0 ...}`) lets the artifact be
  marked announced.  A nil or failed publish leaves it unannounced so a
  retry can still deliver the event.  With no publisher the artifact stays
  unannounced and the caller sees `:announcement :not-configured` instead of
  a fabricated success or a fake failure."
  [component request publish!]
  (let [result (artifacts/register! (:service component) request)
        artifact-id (get-in result [:artifact :id])
        published (when publish! (publish! (:announcement result)))
        accepted? (announcement-accepted? published)]
    (cond
      (and (nil? publish!) (nil? published))
      (assoc result :announced? false :announcement-status :not-configured)

      (not accepted?)
      (throw (announcement-failure
              :ARTIFACT_ANNOUNCEMENT_FAILED
              "artifact announcement was not accepted by the channel"
              {:artifact-id artifact-id
               :session-id (:session-id request)
               :publish-result published
               :announcement (:announcement result)}))

      :else
      (let [marked (artifacts/mark-announced!
                    (:service component)
                    {:session-id (:session-id request)
                     :partition (:partition request)
                     :artifact-id artifact-id})]
        (assoc result :artifact (:artifact marked) :announced? true)))))

(defn- shell-capture
  [result]
  (or (get-in result [:output :artifact])
      (get-in result [:payload :output :artifact])))

(defn- generic-artifact
  [result]
  (or (get-in result [:artifact])
      (get-in result [:payload :artifact])))

(defn executor-result-request
  "Build an artifact request from one Owner executor result, or nil.

  Shell captures keep their truncation marker.  A generic `:artifact` map
  may carry `:path` or an already-finalized `:content-id`; the caller must
  still provide session/task/call provenance.

  `:workspace-root` and `:relative-path` travel with the request so
  registration can prove the file came from the task workspace; the executor
  terminal carries the workspace the call actually ran in."
  [{:keys [session-id task-id call-id executor result inputs
           workspace-root relative-path]}]
  (let [capture (shell-capture result)
        generic (generic-artifact result)
        path (or (:path capture) (:path generic))
        content-id (or (:content-id capture) (:content-id generic))]
    (when (or path content-id)
      (cond-> {:session-id session-id
               :source-task-id (or task-id call-id "executor-result")
               :source-call-id call-id
               :producer {:kind :tool
                          :name (or executor "tool")}
               :inputs (vec (or inputs []))}
        (and path (some? workspace-root))
        (assoc :workspace-root workspace-root)
        (and path (some? relative-path))
        (assoc :relative-path relative-path)
        path (assoc :file path
                    :name (or (:name generic)
                              "shell-output.log")
                    :kind (or (:kind generic) :log))
        content-id (assoc :content-id content-id
                          :name (or (:name generic)
                                    "artifact")
                          :kind (or (:kind generic) :file))
        (:truncated? capture)
        (assoc :truncated? true
               :truncated-reason
               (or (:truncated-reason generic)
                   (str "shell artifact limit "
                        (or (:limit capture) "unknown")
                        " bytes")))))))

(defn registration-diagnostic
  "Structured diagnosis for one failed artifact registration.

  The result artifact may already exist in the workspace while the durable
  document or the announcement is missing; the diagnostic is the only
  record of that gap.  It carries the stable `:code` from the artifact
  error taxonomy, the tool-call identity, the source path and the exception
  message/ex-data, so an operator can find the file and retry."
  [{:keys [session-id task-id call-id executor result workspace-root]}
   t]
  (let [data (ex-data t)
        request (try (executor-result-request
                      {:session-id session-id :task-id task-id :call-id call-id
                       :executor executor :result result
                       :workspace-root workspace-root})
                     (catch Throwable _ nil))
        path (or (:file request)
                 (get-in result [:output :artifact :path])
                 (get-in result [:payload :output :artifact :path])
                 (get-in result [:artifact :path])
                 (get-in result [:payload :artifact :path]))]
    {:code (or (:code data) (:error data) :ARTIFACT_REGISTRATION_FAILED)
     :message (or (.getMessage ^Throwable t) (str t))
     :session-id session-id
     :tool-call-id call-id
     :task-id task-id
     :executor executor
     :path (some-> path str)
     :workspace-root (some-> (or workspace-root (:workspace-root request)) str)
     :artifact-id (or (:artifact-id data)
                      (get-in result [:artifact :id]))
     :retriable? (not (contains? #{:ARTIFACT_OUTSIDE_WORKSPACE
                                   :ARTIFACT_WORKSPACE_REQUIRED
                                   :ARTIFACT_RELATIVE_PATH_MISMATCH
                                   :ARTIFACT_INVALID_REQUEST
                                   :ARTIFACT_SOURCE_MISSING
                                   :ARTIFACT_SOURCE_NOT_REGULAR
                                   :ARTIFACT_EMPTY
                                   :ARTIFACT_LIMIT_EXCEEDED}
                                 (or (:code data) (:error data))))
     :error-data (not-empty (dissoc data :artifacts/expected?))
     :exception-class (.getName (class t))}))

(declare enqueue-failure!)

(defn register-executor-result!
  "Register and announce the artifact produced by one executor result.

  Returns nil when the result has no artifact.  This is the composition seam
  for the Owner; it does not depend on the Owner implementation.

  A failure is never silent.  The thrown error carries `:artifact-diagnostic`
  (code, tool-call id, path, message, ex-data) and the request is queued for
  `retry-pending!`, because the executor already wrote the bytes: the file
  exists in the workspace while no artifact document does."
  [component {:keys [publish!] :as request}]
  (let [request (if (contains? request :workspace-root)
                  request
                  (assoc request :workspace-root
                         (:workspace-root component)))
        artifact-request (executor-result-request request)]
    (when artifact-request
      (try
        (register-and-announce! component artifact-request publish!)
        (catch Throwable t
          (let [data (ex-data t)
                diagnostic (registration-diagnostic request t)]
            (when-let [state (:state component)]
              (enqueue-failure! state artifact-request diagnostic))
            (throw (ex-info (or (.getMessage ^Throwable t)
                                "artifact registration failed")
                            (merge (select-keys data
                                                [:error :artifact-id
                                                 :artifacts/expected?])
                                   {:code (or (:code data) (:error data)
                                              :ARTIFACT_REGISTRATION_FAILED)
                                    :artifact-diagnostic diagnostic})
                            t))))))))

(def ^:private max-pending-registrations
  "Bound on failed registrations kept for an in-process retry."
  32)

(defn- enqueue-failure!
  "Keep one failed registration so a retry can still deliver it.

  Registration can only fail after the executor already wrote the file into
  the workspace, so the request is the only way to try again.  The queue is
  bounded: the newest failures stay visible, and a repeated failure of the
  same source replaces its older entry instead of growing the queue."
  [state request diagnostic]
  (swap! state
         (fn [current]
           (let [path (:file request)
                 same? (fn [entry]
                         (and (= path (get-in entry [:request :file]))
                              (= (:source-call-id request)
                                 (get-in entry [:request :source-call-id]))))
                 existing (some #(when (same? %) %) (:pending current))]
             (-> current
                 (update :pending
                         (fn [pending]
                           (vec (take-last
                                 max-pending-registrations
                                 (conj (vec (remove same? pending))
                                       {:request request
                                        :artifact-diagnostic diagnostic
                                        :attempts (inc (long (or (:attempts existing)
                                                                 0)))})))))
                 (assoc :last-error diagnostic)))))
  state)

(defn pending-registrations
  "Failed registrations still waiting for a retry, oldest first."
  [component]
  (vec (:pending @(get component :state (atom {})))))

(defn last-error
  "The newest registration diagnostic, or nil."
  [component]
  (:last-error @(get component :state (atom {}))))

(defn retry-pending!
  "Retry every pending registration once.

  Returns `{:retried n :succeeded [artifact-ids] :failed [diagnostics]}`.
  Successful entries leave the queue; failures stay with an incremented
  attempt count.  This is the recovery path for bytes the executor already
  wrote to the workspace while the store or the announcement channel was
  unavailable."
  [component publish!]
  (let [state (get component :state (atom {}))
        pending (pending-registrations component)
        results (mapv (fn [{:keys [request]}]
                        (try
                          {:file (:file request)
                           :ok? true
                           :artifact-id (get-in (register-and-announce!
                                                 component request publish!)
                                                [:artifact :id])}
                          (catch Throwable t
                            {:file (:file request)
                             :ok? false
                             :diagnostic (or (:artifact-diagnostic (ex-data t))
                                             (registration-diagnostic request t))})))
                      pending)
        failed (filterv (comp not :ok?) results)]
    (swap! state
           (fn [current]
             (let [failure-for (fn [entry]
                                 (some #(when (and (= (:file entry)
                                                     (:file %))
                                                   (not (:ok? %)))
                                          %)
                                       results))]
               (assoc current
                      :pending
                      (vec (keep (fn [entry]
                                   (when-let [failure (failure-for entry)]
                                     (assoc entry
                                            :artifact-diagnostic
                                            (:diagnostic failure)
                                            :attempts
                                            (inc (long (or (:attempts entry) 0))))))
                                 pending))
                      :last-error (or (:diagnostic (last failed))
                                      (:last-error current))
                      :last-retry-at (System/currentTimeMillis)))))
    {:retried (count pending)
     :succeeded (vec (keep (fn [{:keys [ok? artifact-id]}]
                             (when ok? artifact-id))
                           results))
     :failed (vec (keep (fn [{:keys [ok? diagnostic]}]
                          (when-not ok? diagnostic))
                        results))}))

(defn attach
  "Build an artifact service from application components.

  `opts`:
    :store          open current-state store
    :blob-root      managed blob root
    :config         optional application config; `:limits :artifact-bytes`
                    bounds one artifact and one session's distinct content.
                    `:workspace-root` and `:artifact-root` become the
                    registration source boundary: a file artifact must
                    resolve inside the workspace, while Shell captures stay
                    acceptable under the managed capture root.

  Returns a map with `:service`, thin callables and `:content-type-fn` for the
  gateway's blob route.  It also owns the bounded retry queue for failed
  registrations (`:retry-pending!`, `:pending`, `:last-error`).  A missing
  store or blob root is an expected artifact error, not a silent no-op."
  [{:keys [store blob-root config]}]
  (let [limit (or (get-in config [:limits :artifact-bytes])
                  artifacts/default-max-bytes)
        service (artifacts/service {:store store
                                    :blob-root blob-root
                                    :max-bytes limit
                                    :session-limit-bytes limit
                                    :workspace-root (:workspace-root config)
                                    :capture-root (:artifact-root config)})
        types (atom {})
        state (atom {:pending [] :last-error nil})
        list (fn [request]
               (let [result (artifacts/list-artifacts service request)]
                 (remember-types! types (:artifacts result))
                 result))
        register! (fn [request]
                    (let [result (artifacts/register! service request)]
                      (remember-types! types [(:artifact result)])
                      result))]
    {:service service
     :workspace-root (:workspace-root config)
     :capture-root (:artifact-root config)
     :state state
     :register! register!
     :register-shell-output! (fn [request shell-result]
                               (let [result (artifacts/register-shell-output!
                                             service request shell-result)]
                                 (remember-types! types [(:artifact result)])
                                 result))
     :retract! (fn [request] (artifacts/retract! service request))
     :mark-announced! (fn [request] (artifacts/mark-announced! service request))
     :list list
     :get (fn [request]
            (let [result (artifacts/get-artifact service request)]
              (remember-types! types [result])
              result))
     :download (fn [artifact] (artifacts/download service artifact))
     :open! (fn [artifact] (artifacts/open! service artifact))
     :verify! (fn [artifact] (artifacts/verify! service artifact))
     ;; The queue is owned by this component; callers pass only the
     ;; arguments that vary per call.
     :pending (fn [] (pending-registrations {:state state}))
     :last-error (fn [] (last-error {:state state}))
     :retry-pending! (fn [publish!]
                       (retry-pending! {:service service :state state} publish!))
     :session-budget (fn [request]
                       (artifacts/session-budget service request))
     :content-type-fn (fn [content-id _metadata]
                        (get @types content-id))
     :snapshot (fn [session-id]
                 (let [result (list {:session-id session-id})]
                   {:artifacts (:artifacts result)
                    :version (:version result)
                    ;; A failed registration is queryable from the same
                    ;; snapshot an operator already reads.
                    :pending-registrations
                    (->> (pending-registrations {:state state})
                         (filter #(= session-id
                                     (get-in % [:request :session-id])))
                         (mapv :artifact-diagnostic)
                         vec)
                    :last-error (last-error {:state state})}))}))
