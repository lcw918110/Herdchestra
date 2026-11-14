(ns hc.client.workbench.notices-test
  "Notice lifecycle regression: de-duplication, danger retention, bulk
  dismissal, automatic expiry, and the rendered stack.

  The workbench notices used to only accumulate: a repeated notice took a new
  slot, an ordinary notice never left, and only one toast could be closed at a
  time.  The mutation tests drive the real mutations on a headless Fulcro app
  whose transactions run synchronously.  The render tests mount the shared
  toast stack in jsdom, so the count badge and the bulk close control are
  checked as they actually appear.

  Node-only verification; it is not part of the production build.  Reproduce
  with the harness shape of `test/js/verify_workbench_shell_dom.sh`: copy its
  `cljs.main` invocation, point `:main` at
  `hc.client.workbench.notices-test/-main`, compile this namespace, then run
  `node test/js/run_workbench_shell_dom_tests.cjs <out-dir>` (that runner
  installs jsdom before React loads, which the rendered-stack tests need)."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [clojure.string :as str]
            [cljs.test :refer-macros [deftest is async run-tests use-fixtures]]
            [com.fulcrologic.fulcro.application :as application]
            [com.fulcrologic.fulcro.components :as comp]
            [hc.client.workbench.mutations :as mutations]
            [hc.client.workbench.views.common :as common]))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(comp/defsc FauxRoot
  [_this _props]
  {:query [:ui/notices]
   :ident (fn [] [:root/id :root])}
  nil)

;; Async tests require the map form: cljs.test cannot sequence a function
;; fixture around a test that returns before its callback runs.
(use-fixtures :each
  {:after (fn [_] (mutations/clear-notice-timers!))})

(defn- app
  []
  (application/headless-synchronous-app FauxRoot {:initial-db {:ui/notices []}}))

(defn- notices
  [app]
  (:ui/notices (application/current-state app)))

(defn- notify!
  [app notice]
  (comp/transact! app [(mutations/notify {:notice notice})]))

(defn- plain
  [text]
  {:id (str "n-" (random-uuid)) :tone :warn :text text :at "now"})

(deftest repeated-notice-accumulates-a-count
  (let [app (app)
        first-notice (plain "实时通道已恢复")]
    (notify! app first-notice)
    (notify! app (assoc first-notice :id (str "n-" (random-uuid))))
    (notify! app (assoc first-notice :id (str "n-" (random-uuid))))
    (is (= 1 (count (notices app)))
        "the same tone and text must occupy one slot")
    (is (= 3 (:count (first (notices app))))
        "each repetition increments the visible count")
    (is (= (:id first-notice) (:id (first (notices app))))
        "the original id survives, so its dismiss control stays valid")))

(deftest explicit-key-decides-identity
  (let [app (app)]
    (notify! app {:id "a" :key :sync :tone :warn :text "第一次"})
    (notify! app {:id "b" :key :sync :tone :warn :text "第二次"})
    (is (= 1 (count (notices app))))
    (is (= 2 (:count (first (notices app)))))
    (is (= "第二次" (:text (first (notices app))))
        "the newest text replaces the older one")
    (notify! app {:id "c" :key :other :tone :warn :text "第二次"})
    (is (= 2 (count (notices app)))
        "a different key keeps a separate slot")))

(deftest danger-notices-never-expire
  (let [app (app)]
    (notify! app {:id "boom" :tone :danger :text "命令未发送"})
    (is (= 1 (count (notices app))))
    (is (= "boom" (:id (first (notices app)))))))

(deftest dismiss-all-clears-the-stack
  (let [app (app)]
    (notify! app (plain "一"))
    (notify! app (plain "二"))
    (notify! app {:id "boom" :tone :danger :text "三"})
    (is (= 3 (count (notices app))))
    (comp/transact! app [(mutations/dismiss-all-notices {})])
    (is (empty? (notices app))
        "bulk dismissal removes danger notices too, on the user's request")))

(deftest ordinary-notice-expires
  (async done
         (let [app (app)]
           ;; The delay is read when the timer is scheduled, so a short rebind
           ;; keeps this test fast without touching production behaviour.
           (with-redefs [mutations/notice-auto-dismiss-ms 20]
             (notify! app {:id "fresh" :tone :info :text "已保存"})
             (notify! app {:id "boom" :tone :danger :text "故障保留"}))
           (is (= 2 (count (notices app)))
               "both notices are visible before the timer fires")
           (js/setTimeout
            (fn []
              (is (= ["故障保留"] (mapv :text (notices app)))
                  "the ordinary notice expired; the danger notice remained")
              (done))
            120))))

;; ---------------------------------------------------------------------------
;; Rendered stack

(defn- act!
  [f]
  ((.-act react) f))

(defn- render!
  "Mount ELEMENT in a fresh jsdom container and return it with its root."
  [element]
  (let [container (.createElement js/document "div")]
    (.appendChild js/document.body container)
    (let [root (rdc/createRoot container)]
      (act! #(.render root element))
      [container root])))

(defn- unmount!
  [[container root]]
  (act! #(.unmount root))
  (.remove container))

(defn- bulk-close-button
  [container]
  (.querySelector container "button[aria-label='关闭全部通知']"))

(deftest stack-renders-count-and-bulk-close
  (let [dismissed (atom [])
        view (render! (common/notice-stack
                       [{:id "a" :tone :warn :text "一" :count 3}
                        {:id "b" :tone :danger :text "二"}]
                       (fn [id] (swap! dismissed conj id))
                       (fn [] (swap! dismissed conj :all))))]
    (try
      (is (some? (bulk-close-button (first view)))
          "the stack offers a bulk close control")
      (is (str/includes? (.-textContent (bulk-close-button (first view)))
                         "全部关闭"))
      (is (some? (.querySelector (first view)
                                 ".wb-toast[data-tone='warn'] .wb-badge"))
          "a repeated notice shows its occurrence count badge")
      (is (nil? (.querySelector (first view)
                                ".wb-toast[data-tone='danger'] .wb-badge"))
          "a single occurrence renders no count badge")
      (let [button (bulk-close-button (first view))]
        (act! #(.click button))
        (is (= [:all] @dismissed)
            "the bulk control invokes its handler"))
      (finally (unmount! view)))))

(deftest stack-without-bulk-handler-keeps-legacy-behaviour
  (let [view (render! (common/notice-stack
                       [{:id "a" :tone :info :text "一"}]
                       (fn [_])))]
    (try
      (is (nil? (bulk-close-button (first view)))
          "callers that pass no bulk handler see no bulk control")
      (finally (unmount! view)))))

(defn -main
  []
  (run-tests 'hc.client.workbench.notices-test))

(set! *main-cli-fn* -main)
