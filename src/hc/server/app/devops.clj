(ns hc.server.app.devops
  "Application adapter for the real Plan dedicated worker.

  `hc.compose.mission.devops` owns the worker loop.  This namespace supplies
  the two host-owned ports:

  - the session model gateway (`:start!` / `:cancel!`);
  - the read-only tool executor backed by `hc.server.files` and
    `hc.core.text-location`.

  The adapter never exposes a write, shell, or board capability.  `park` and
  `unpark` are handled by the worker loop before this executor: `park`
  interrupts the current devops call with a parked receipt, and a later
  steer on the same worker can `unpark` that continuation exactly once."
  (:require [clojure.string :as str]
            [hc.compose.mission.devops :as devops]
            [hc.core.text-location :as text-location]
            [hc.core.ufss :as ufss]
            [hc.server.files :as files]))

(defn- structured-text
  [value]
  (try
    (ufss/write-str value)
    (catch Throwable error
      (ufss/write-str
       {:status :UNSUPPORTED_OPERATION
        :message "the tool result could not be rendered as EDN+UFSS"
        :detail (or (.getMessage error) (str error))}))))

(defn- payload-text
  [value]
  (cond
    (string? value) value
    (nil? value) ""
    :else (structured-text value)))

(defn- result-text
  [result]
  (cond
    (string? result) result
    (map? result)
    (cond
      (contains? result :text) (payload-text (:text result))
      (contains? result :report) (payload-text (:report result))
      :else (structured-text result))
    :else (structured-text result)))

(defn- tool-ok
  [result]
  {:status :ok :text (result-text result)})

(defn- tool-failed
  [message]
  {:status :failed
   :text (structured-text
          (if (map? message)
            (cond-> message
              (not (contains? message :status))
              (assoc :status :failed))
            {:status :failed :message (str message)}))})

(defn- read-path
  [files-service path]
  (let [result (files/read-text files-service (or path ""))]
    (if (:ok? result)
      {:ok? true :text (:text result)}
      {:ok? false
       :error (or (:error result)
                  {:code :READ_FAILED
                   :message "file read failed"})})))

;; ---------------------------------------------------------------------------
;; Recoverable worker-call ledger (D-47)

(def ^:private label-limit
  "Characters of a helper label kept as server-side evidence."
  200)

(defn- bounded-label
  [value]
  (let [text (cond
               (nil? value) nil
               (string? value) value
               :else (try
                       (ufss/write-str value)
                       (catch Throwable _ (str value))))
        text (str/trim (str text))]
    (when-not (str/blank? text)
      (subs text 0 (min label-limit (count text))))))

(defn- call-label
  "One short printable label for a host helper invocation.

  A command helper carries the command text in `:command`; another helper
  carries its name.  The label is evidence for the operator, never a
  credential store, so it is bounded."
  [helper arguments]
  (or (bounded-label (:command arguments))
      (bounded-label helper)
      "host helper call"))

(defn- helper-outcome
  "Map one host helper result to one ledger outcome.

  Recognized shell statuses keep their meaning: `:COMPLETED` with a
  non-zero exit code is `:failed`, `:CANCELLED` is `:cancelled`, and
  `:UNCERTAIN`/`:TIMEOUT` or a pending board publication is
  `:outcome-unknown`.  Anything else is `:failed` unless the helper
  returned a value with no status at all: an unknown shape must never be
  reported as success."
  [result]
  (if-not (map? result)
    :succeeded
    (let [raw (:status result)
          status (when (or (keyword? raw) (string? raw))
                   (keyword (str/lower-case (name raw))))
          exit (:exit-code result)]
      (cond
        (true? (:cancelled? result)) :cancelled
        (contains? #{:cancelled :canceled} status) :cancelled
        (contains? #{:uncertain :timeout} status) :outcome-unknown
        (false? (:published? result)) :outcome-unknown
        (contains? #{:completed :ok :succeeded :finished} status)
        (if (and (integer? exit) (not (zero? (long exit))))
          :failed
          :succeeded)
        (contains? #{:failed :error} status) :failed
        (nil? status) :succeeded
        :else :failed))))

(defn- call-summary
  "Bounded executor evidence kept with a ledger record.

  The captured stdout/stderr and the full request stay in the shell
  session; the Owner ledger holds only what an operator needs to decide
  what to check."
  [result]
  (when (map? result)
    (select-keys result
                 [:request-id :status :exit-code :duration-ms
                  :cancelled? :published?])))

(defn- ledger-accepted?
  [response]
  (boolean
   (or (true? (:ok? response))
       (contains? #{:accepted :replayed} (:status response)))))

(defn- ledger-start!
  [ledger entry]
  (try
    ((:start! ledger) entry)
    (catch Throwable error
      {:ok? false
       :error {:code :WORKER_CALL_LEDGER_FAILED
               :message (or (.getMessage error) (str error))}})))

(defn- ledger-finish!
  "Settle one ledger record.  A failure here is never a helper failure: the
  external action already happened, so a record that stays `:running` is
  the honest evidence and recovery turns it into `:outcome-unknown`."
  [ledger call-id outcome result error]
  (try
    ((:finish! ledger)
     (cond-> {:call-id call-id :outcome outcome}
       (some? result) (assoc :summary (call-summary result))
       (some? error) (assoc :error error)))
    (catch Throwable _ nil))
  nil)

(defn- ledgered-call
  "Run one host helper after registering it in the recoverable ledger.

  A helper whose start the host refused does not run: an external action
  that cannot be recorded must not happen (AGENTS.md §5.3).  The start is
  committed before the helper begins, so a hard process stop leaves a
  `:running` record that restart recovery marks `:outcome-unknown`."
  [{:keys [ledger helper helper-name arguments worker-id tenure-id
           worker-call-id]}]
  (let [call-id (str "worker-call-" (random-uuid))
        started (ledger-start! ledger
                               {:call-id call-id
                                :worker-id worker-id
                                :tenure-id tenure-id
                                :worker-call-id worker-call-id
                                :helper helper-name
                                :label (call-label helper-name arguments)})]
    (if-not (ledger-accepted? started)
      (do
        ;; The helper did not run.  A start that was merely unconfirmed may
        ;; still have committed, so settle it as `:failed` with that reason;
        ;; a missing record makes this a no-op.  A duplicate id names an
        ;; older real action and must keep its own evidence.
        (when-not (= :WORKER_CALL_DUPLICATE (get-in started [:error :code]))
          (ledger-finish! ledger call-id :failed nil
                          {:code :WORKER_CALL_START_UNCONFIRMED
                           :message (str "the " helper-name
                                         " invocation was not confirmed by "
                                         "the Owner, so it was not run")
                           :detail (:error started)}))
        (tool-failed
         {:status :failed
          :code :WORKER_CALL_UNRECORDED
          :message (str "the host did not record this " helper-name
                        " invocation in the recoverable ledger, so it was "
                        "not run")
          :detail (:error started)}))
      (try
        (let [result (helper arguments)]
          (ledger-finish! ledger call-id (helper-outcome result) result nil)
          (tool-ok result))
        (catch Throwable error
          (ledger-finish! ledger call-id :failed nil
                          (or (.getMessage error) (str error)))
          (throw error))))))

(defn tool-executor
  "Build the read-only tool executor for one workspace.

  `:files-service` is required.  `:helpers` optionally maps helper names to
  functions used by the `call` capability.  No helper is available unless the
  host supplies it.  Structured values are returned as EDN+UFSS text; `cat`
  returns the file body verbatim.

  `:call-ledger` optionally supplies the host's recoverable ledger ports
  `:start!` and `:finish!`.  When present, every `call` invocation is
  registered before the helper runs, so a hard process stop leaves a
  `:running` record that restart recovery marks `:outcome-unknown` (D-47).
  A refused registration refuses the helper: an external action that cannot
  be recorded must not happen."
  [{:keys [files-service helpers call-ledger]}]
  {:execute!
   (fn [{:keys [name arguments] :as invocation}]
     (let [arguments (or arguments {})
           name (str name)]
       (try
         (case name
           "cat"
           (let [read (read-path files-service (:path arguments))]
             (if (:ok? read)
               (tool-ok (:text read))
               (tool-failed (:error read))))

           "stat"
           (tool-ok (files/stat-path files-service (:path arguments)))

           "ls"
           (tool-ok (files/list-entries files-service (:path arguments)))

           "where"
           (let [read (if (contains? arguments :content)
                        {:ok? true :text (:content arguments)}
                        (read-path files-service (:path arguments)))]
             (if (:ok? read)
               (tool-ok (text-location/where {:content (:text read)
                                              :needle (:needle arguments)}))
               (tool-failed (:error read))))

           "whereAll"
           (let [read (if (contains? arguments :content)
                        {:ok? true :text (:content arguments)}
                        (read-path files-service (:path arguments)))]
             (if (:ok? read)
               (tool-ok (text-location/whereAll
                         {:content (:text read)
                          :needle (:needle arguments)}))
               (tool-failed (:error read))))

           "call"
           (let [helper-name (keyword (str (:name arguments)))
                 helper (get helpers helper-name)]
             (cond
               (not (fn? helper))
               (tool-failed (str "no host helper named " (:name arguments)))

               call-ledger
               (ledgered-call {:ledger call-ledger
                               :helper helper
                               :helper-name (str (:name arguments))
                               :arguments (:arguments arguments)
                               :worker-id (:worker-id invocation)
                               :tenure-id (:tenure-id invocation)
                               :worker-call-id (:call-id invocation)})

               :else
               (tool-ok (helper (:arguments arguments)))))

           (tool-failed (str "unknown devops tool " name)))
         (catch Throwable t
           (tool-failed (or (.getMessage t) (str t)))))))})

(defn runner
  "Build a real Plan worker runner for one session.

  Options:
  - `:model-gateway` the session gateway with `:start!` / `:cancel!`.
  - `:files-service` the workspace read-only service.
  - `:helpers` optional read-only helper functions for `call`.
  - `:max-turns`, `:timeout-ms`, `:clock` passed to the worker runner."
  [{:keys [model-gateway files-service] :as opts}]
  (when-not (and (map? model-gateway)
                 (ifn? (:start! model-gateway))
                 (ifn? (:cancel! model-gateway)))
    (throw (ex-info "devops runner needs a session model gateway"
                    {:code :MODEL_GATEWAY_REQUIRED})))
  (devops/runner (assoc opts
                        :model model-gateway
                        :tools (tool-executor opts))))

(defn run-devops!
  "Run one synchronous Plan devops call through an injected command port.

  `:command!` receives the mission command map and returns the mission host
  result shape `{:ok? true :receipt ... :effects [...]}` or a refusal.  The
  application supplies it from its single Owner commit boundary; this
  function never commits state itself.

  The returned value is the completion result.  The report is the worker's
  full text, not a summary."
  [{:keys [runner command!]} {:keys [tenure-id call-id verbose-intent]
                              :as request}]
  (when-not (fn? command!)
    (throw (ex-info "run-devops! needs a :command! function"
                    {:code :COMMAND_PORT_REQUIRED})))
  (when-not (and (string? tenure-id) (not (str/blank? tenure-id)))
    (throw (ex-info "run-devops! needs a non-blank :tenure-id"
                    {:code :TENURE_ID_REQUIRED})))
  (when-not (and (string? call-id) (not (str/blank? call-id)))
    (throw (ex-info "run-devops! needs a non-blank :call-id"
                    {:code :CALL_ID_REQUIRED})))
  (when-not (and (string? verbose-intent) (not (str/blank? verbose-intent)))
    (throw (ex-info "run-devops! needs a non-blank :verbose-intent"
                    {:code :VERBOSE_INTENT_REQUIRED})))
  (let [requested (command! {:op :devops-request
                             :tenure-id tenure-id
                             :call-id call-id
                             :verbose-intent verbose-intent})]
    (if (false? (:ok? requested))
      requested
      (let [receipt (or (:receipt requested) requested)
            effect (or (first (:effects requested))
                       (when (:worker-id receipt)
                         {:type (if (:reused? receipt)
                                  :worker/steer
                                  :worker/spawn)
                          :worker-id (:worker-id receipt)
                          :tenure-id tenure-id
                          :call-id call-id
                          :verbose-intent verbose-intent
                          :model-role (or (:model-role receipt) :task)
                          :model-id (:model-id receipt)
                          :capabilities (:capabilities receipt)
                          :contract (:contract receipt)}))]
        (if-not (and (:worker-id effect) (:model-id effect))
          {:ok? false
           :error {:code :DEVOPS_EFFECT_MISSING
                   :message "devops request receipt has no worker identity"
                   :receipt receipt}}
        (let [outcome (try
                        ((:run! runner) effect)
                        (catch Throwable t
                          {:status :failed
                           :report (or (.getMessage t) (str t))}))]
          (command! {:op :devops-complete
                     :tenure-id tenure-id
                     :call-id call-id
                     :worker-id (:worker-id effect)
                     :status (:status outcome)
                     :report (:report outcome)}))
          )))))
