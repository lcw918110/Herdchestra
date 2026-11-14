(ns hc.client.workbench.contrast-test
  "Machine-checked WCAG 2.x contrast for the workbench palette.

  The test parses `resources/public/css/workbench.css` as the single source of
  the theme palettes: light, dark, and high contrast each resolve from their
  own declaration block, so a high-contrast run cannot silently reuse the
  light palette.  Banner rules live in the conversation surface's inline CSS,
  which this test also reads; their backgrounds are solid `--wb-banner-*`
  tokens declared in `workbench.css`.

  It supports #rgb/#rrggbb, rgb(), rgba(), var() recursion, and alpha
  compositing over an opaque background.  Values that cannot be resolved
  without guessing — color-mix(), gradients, and image backgrounds — are
  listed in :skipped.  A skipped item may never be one of the required probes.

  Limitation: this is a color-pair calculation, not a screenshot.  It proves
  the declared foreground/background pair, not anti-aliasing, font weight,
  translucency stacking, or themed third-party widgets."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]])
  (:import [java.util.regex Pattern]))

(def ^:private css-path "resources/public/css/workbench.css")

(def ^:private conversation-view-path "src/hc/client/conversation/view.cljs")

(def ^:private conversation-css-marker "(def ^:private css")

(def ^:private project-root
  (or (System/getProperty "hc.project.root")
      (System/getProperty "user.dir")))

(defn- css-source []
  (slurp (io/file project-root css-path)))

(defn- conversation-css
  "Return the inline stylesheet of the conversation surface, unescaped.

  The surface owns its `.hc-*` rules, so the banner declarations are read
  from here instead of being copied into the workbench stylesheet.  The
  extraction walks a real Clojure string literal (quote plus backslash
  escapes) rather than regexing the whole file."
  []
  (let [text (slurp (io/file project-root conversation-view-path))
        marker-at (string/index-of text conversation-css-marker)
        open (when marker-at (string/index-of text "\"" marker-at))
        close (when open
                (loop [i (inc open) escaped? false]
                  (when (< i (count text))
                    (let [character (.charAt text i)]
                      (cond
                        escaped? (recur (inc i) false)
                        (= character \\) (recur (inc i) true)
                        (= character \") i
                        :else (recur (inc i) false))))))]
    (when (and open close)
      (string/replace (subs text (inc open) close) "\\\"" "\""))))

(defn- probe-css
  "workbench.css followed by the surface's inline CSS.  The concatenation
  mirrors load order: a rule in the surface wins over an equal-specificity
  rule in the linked stylesheet."
  []
  (str (css-source) "\n" (conversation-css)))

(defn- strip-comments [css]
  (string/replace css #"(?s)/\*.*?\*/" ""))

(defn- css-blocks [css]
  (for [[_ selector body]
        (re-seq #"(?s)([^{}]+)\{([^{}]*)\}" (strip-comments css))]
    {:selector (string/trim selector)
     :body body}))

(defn- declarations [body]
  (for [[_ property value]
        (re-seq #"(?s)([-\w]+)\s*:\s*([^;]+)" body)]
    [(string/trim property) (string/trim value)]))

(defn- selector-parts [selector]
  (mapv string/trim (string/split selector #",")))

(defn- selector-match? [selector wanted]
  (some #(= wanted %) (selector-parts selector)))

(defn- block-vars [css selector]
  (->> (css-blocks css)
       (filter #(selector-match? (:selector %) selector))
       (mapcat #(declarations (:body %)))
       (filter (fn [[property _]] (string/starts-with? property "--")))
       (into {})))

(defn- theme-vars [css]
  (let [root (block-vars css ":root")
        light (block-vars css "html[data-theme=\"light\"]")
        dark (block-vars css "html[data-theme=\"dark\"]")
        high (block-vars css "html[data-contrast=\"high\"]")]
    {:light (merge root light)
     :dark (merge root dark)
     ;; High contrast is resolved from its own block only.  Merging the light
     ;; block would let a missing high-contrast declaration inherit a light
     ;; value and pass silently, which is the drift this test must catch.
     ;; `:high-block` exposes the raw block so a test can prove it is not
     ;; empty and differs from light.
     :high-contrast (merge root high)
     :high-block high}))

;; ---------------------------------------------------------------------------
;; Color parsing and WCAG math

(defn- clamp [value low high]
  (max low (min high value)))

(defn- parse-channel [token]
  (let [token (string/trim token)]
    (if (string/ends-with? token "%")
      (* 255 (/ (Double/parseDouble
                 (subs token 0 (dec (count token))))
                100))
      (Double/parseDouble token))))

(defn- parse-alpha [token]
  (if (nil? token)
    1.0
    (let [token (string/trim token)]
      (if (string/ends-with? token "%")
        (/ (Double/parseDouble
            (subs token 0 (dec (count token))))
           100)
        (Double/parseDouble token)))))

(defn- parse-color
  "Parse #rgb/#rrggbb/#rgba/#rrggbbaa, rgb(), rgba(), transparent.
  Returns {:r :g :b :a} with channels 0–255 and alpha 0–1, or nil."
  [value]
  (let [value (-> (str value)
                  string/trim
                  (string/replace #"(?i)\s*!important$" ""))
        lower (string/lower-case value)]
    (cond
      (= lower "transparent")
      {:r 0.0 :g 0.0 :b 0.0 :a 0.0}

      (re-matches #"(?i)#[0-9a-f]{3}" value)
      (let [digits (subs value 1)
            [r g b] (mapv #(Integer/parseInt (str % %) 16) digits)]
        {:r (double r) :g (double g) :b (double b) :a 1.0})

      (re-matches #"(?i)#[0-9a-f]{4}" value)
      (let [digits (subs value 1)
            [r g b a] (mapv #(Integer/parseInt (str % %) 16) digits)]
        {:r (double r) :g (double g) :b (double b)
         :a (/ (double a) 255.0)})

      (re-matches #"(?i)#[0-9a-f]{6}" value)
      (let [digits (subs value 1)
            [r g b] (mapv #(Integer/parseInt % 16)
                          (mapv #(subs digits (* 2 %) (* 2 (inc %)))
                                (range 3)))]
        {:r (double r) :g (double g) :b (double b) :a 1.0})

      (re-matches #"(?i)#[0-9a-f]{8}" value)
      (let [digits (subs value 1)
            [r g b a] (mapv #(Integer/parseInt % 16)
                            (mapv #(subs digits (* 2 %) (* 2 (inc %)))
                                  (range 4)))]
        {:r (double r) :g (double g) :b (double b)
         :a (/ (double a) 255.0)})

      (re-matches #"(?i)rgba?\([^)]*\)" value)
      (let [[_ fn-name body] (re-matches #"(?i)(rgb|rgba)\(([^)]*)\)" value)
            tokens (vec (remove string/blank?
                                (string/split body #"[\s,]+")))
            slash (first (keep-indexed #(when (= "/" %2) %1) tokens))
            components (if (some? slash)
                         (take slash tokens)
                         (take 3 tokens))
            alpha-token (cond
                          (some? slash) (nth tokens (inc slash) nil)
                          (= 4 (count tokens)) (nth tokens 3 nil)
                          :else nil)
            [r g b] (mapv parse-channel components)]
        {:r (clamp r 0 255)
         :g (clamp g 0 255)
         :b (clamp b 0 255)
         :a (clamp (parse-alpha alpha-token) 0 1)})

      :else nil)))

(defn- opaque [color]
  (>= (:a color) 0.999))

(defn- composite
  "Composite foreground COLOR over opaque BACKGROUND."
  [color background]
  (if (opaque color)
    (assoc color :a 1.0)
    (let [a (:a color)
          mix (fn [foreground backdrop]
                (+ (* foreground a)
                   (* backdrop (- 1.0 a))))]
      {:r (mix (:r color) (:r background))
       :g (mix (:g color) (:g background))
       :b (mix (:b color) (:b background))
       :a 1.0})))

(defn- channel-luminance [channel]
  (let [c (/ (double channel) 255.0)]
    (if (<= c 0.04045)
      (/ c 12.92)
      (Math/pow (/ (+ c 0.055) 1.055) 2.4))))

(defn- relative-luminance [{:keys [r g b]}]
  (+ (* 0.2126 (channel-luminance r))
     (* 0.7152 (channel-luminance g))
     (* 0.0722 (channel-luminance b))))

(defn- contrast-ratio
  "WCAG 2.x contrast ratio of FG text over BG.  Both colors may carry alpha;
  FG alpha is composited over BG."
  [fg bg]
  (let [fg (composite fg bg)
        l1 (relative-luminance fg)
        l2 (relative-luminance bg)
        [high low] (if (>= l1 l2) [l1 l2] [l2 l1])]
    (/ (+ high 0.05) (+ low 0.05))))

;; ---------------------------------------------------------------------------
;; var() and declaration resolution

(defn- matching-close [text open-index]
  (loop [i (inc open-index) depth 1]
    (when (< i (count text))
      (let [character (.charAt text i)]
        (cond
          (= character \() (recur (inc i) (inc depth))
          (= character \)) (if (= depth 1)
                             i
                             (recur (inc i) (dec depth)))
          :else (recur (inc i) depth))))))

(defn- split-var-arguments [inside]
  (loop [i 0 depth 0]
    (if (>= i (count inside))
      [(string/trim inside) nil]
      (let [character (.charAt inside i)]
        (cond
          (= character \() (recur (inc i) (inc depth))
          (= character \)) (recur (inc i) (dec depth))
          (and (= character \,) (zero? depth))
          [(string/trim (subs inside 0 i))
           (string/trim (subs inside (inc i)))]
          :else (recur (inc i) depth))))))

(defn- skipped [reason value]
  {:status :skipped :reason reason :value value})

(defn- resolve-vars
  "Replace var(--name[, fallback]) recursively.  Never guesses color-mix,
  gradients, or images; those return a :skipped marker."
  [theme value]
  (loop [text (string/trim (str value)) depth 0]
    (cond
      (string/includes? text "color-mix(")
      (skipped "color-mix" text)

      (re-find #"(?i)(linear|radial|conic)-gradient\(" text)
      (skipped "gradient" text)

      (string/includes? text "url(")
      (skipped "image" text)

      (not (string/includes? text "var("))
      {:status :ok :value text}

      (> depth 32)
      (skipped "var recursion" text)

      :else
      (let [start (string/index-of text "var(")
            open (+ start 3)
            close (matching-close text open)]
        (if (nil? close)
          (skipped "unclosed var()" text)
          (let [inside (subs text (inc open) close)
                [name fallback] (split-var-arguments inside)
                raw (get theme (string/trim name))
                replacement (or (some-> raw string/trim)
                                fallback)]
            (if (or (nil? replacement)
                    (string/blank? replacement))
              (skipped (str "unresolved " (string/trim name)) text)
              (recur (str (subs text 0 start)
                          replacement
                          (subs text (inc close)))
                     (inc depth)))))))))

(defn- declaration-value [css selector property]
  (->> (css-blocks css)
       (filter #(selector-match? (:selector %) selector))
       (mapcat #(declarations (:body %)))
       (filter (fn [[candidate _]] (= property candidate)))
       last
       second))

(defn- source-value [theme css source]
  (cond
    (:var source) (get theme (:var source))
    (:rule source) (declaration-value css (:rule source) (:property source))
    :else nil))

(defn- source-label [source]
  (cond
    (:var source) (:var source)
    (:rule source) (str (:rule source) "[" (:property source) "]")
    :else "unknown source"))

(defn- resolved-color [theme css source]
  (let [raw (source-value theme css source)]
    (if (nil? raw)
      (skipped "missing declaration" nil)
      (let [resolved (resolve-vars theme raw)]
        (if (= :skipped (:status resolved))
          (assoc resolved :raw raw)
          (if-let [color (parse-color (:value resolved))]
            {:status :ok
             :raw raw
             :resolved (:value resolved)
             :color color}
            (skipped (str "unsupported color " (:value resolved))
                     raw)))))))

;; ---------------------------------------------------------------------------
;; Probes

(def ^:private base-palette-vars
  ["--wb-bg" "--wb-surface" "--wb-surface-2"
   "--wb-ink" "--wb-ink-muted" "--wb-line"
   "--wb-accent" "--wb-accent-ink"
   "--wb-danger" "--wb-warn" "--wb-ok"])

(def ^:private banner-vars
  ["--wb-banner-error-bg" "--wb-banner-warning-bg" "--wb-banner-info-bg"])

(def ^:private palette-vars (into base-palette-vars banner-vars))

(def ^:private all-themes [:light :dark :high-contrast])

(def ^:private high-only [:high-contrast])

(def ^:private required-probe-ids
  #{:body :muted :accent-link :error-bar :success-bar :primary-button
    :banner-error :banner-warning :banner-info})

(def ^:private high-contrast-probe-ids
  #{:hc-surface-muted :hc-border :hc-accent :hc-danger :hc-warn :hc-ok})

(defn- contrast-probes []
  [{:id :body
    :label "正文"
    :fg {:var "--wb-ink"}
    :bg {:var "--wb-bg"}
    :minimum 4.5}

   {:id :muted
    :label "次要文字"
    :fg {:var "--wb-ink-muted"}
    :bg {:var "--wb-bg"}
    :minimum 4.5}

   {:id :accent-link
    :label "链接/accent 文字"
    :fg {:rule ".hc-data-subnav a" :property "color"}
    :bg {:var "--wb-bg"}
    :minimum 4.5}

   {:id :error-bar
    :label "错误条文字 / .hc-message 背景"
    :fg {:rule ".hc-message--error" :property "color"}
    :bg {:rule ".hc-message" :property "background"}
    :minimum 4.5}

   {:id :success-bar
    :label "成功条文字 / .hc-panel 背景"
    :fg {:rule ".hc-badge--synced" :property "color"}
    :bg {:rule ".hc-panel" :property "background"}
    :minimum 4.5}

   {:id :primary-button
    :label "主按钮文字/背景（深色 H-17）"
    :fg {:rule ".hc-button-primary" :property "color"}
    :bg {:rule ".hc-button-primary" :property "background"}
    :minimum 4.5}

   ;; Banner bodies.  The surface paints its own rules; the backgrounds are
   ;; solid --wb-banner-* tokens that workbench.css declares per theme.
   {:id :banner-error
    :label "错误横幅文字/背景"
    :fg {:rule ".hc-banner-error" :property "color"}
    :bg {:rule ".hc-banner-error" :property "background"}
    :minimum 4.5}

   {:id :banner-warning
    :label "警告横幅文字/背景"
    :fg {:rule ".hc-banner-warning" :property "color"}
    :bg {:rule ".hc-banner-warning" :property "background"}
    :minimum 4.5}

   {:id :banner-info
    :label "信息横幅文字/背景"
    :fg {:rule ".hc-banner-info" :property "color"}
    :bg {:rule ".hc-banner-info" :property "background"}
    :minimum 4.5}

   ;; High contrast gets its own probes.  `data-contrast="high"` may sit on
   ;; top of any theme, so its muted text, borders and status colours must be
   ;; verified against the palette that actually wins, not against light.
   {:id :hc-surface-muted
    :label "高对比：卡片上的次要文字"
    :themes high-only
    :fg {:var "--wb-ink-muted"}
    :bg {:var "--wb-surface"}
    :minimum 4.5}

   {:id :hc-border
    :label "高对比：边框线"
    :themes high-only
    :fg {:var "--wb-line"}
    :bg {:var "--wb-surface"}
    :minimum 3.0}      ; WCAG 1.4.11 non-text contrast

   {:id :hc-accent
    :label "高对比：accent 文字"
    :themes high-only
    :fg {:var "--wb-accent"}
    :bg {:var "--wb-surface"}
    :minimum 4.5}

   {:id :hc-danger
    :label "高对比：danger 文字"
    :themes high-only
    :fg {:var "--wb-danger"}
    :bg {:var "--wb-surface"}
    :minimum 4.5}

   {:id :hc-warn
    :label "高对比：warn 文字"
    :themes high-only
    :fg {:var "--wb-warn"}
    :bg {:var "--wb-surface"}
    :minimum 4.5}

   {:id :hc-ok
    :label "高对比：ok 文字"
    :themes high-only
    :fg {:var "--wb-ok"}
    :bg {:var "--wb-surface"}
    :minimum 4.5}])

(defn- probes-for-theme [theme]
  (filter #(contains? (set (or (:themes %) all-themes)) theme)
          (contrast-probes)))

(defn- palette-tokens
  "Resolve every palette token to {:var :color}.  A token that does not
  resolve is kept with a nil :color so its failure is visible to the caller
  instead of shrinking the collection."
  [theme]
  (mapv (fn [variable]
          (let [raw (get theme variable)
                resolved (when raw (resolve-vars theme raw))]
            {:var variable
             :color (when (= :ok (:status resolved))
                      (parse-color (:value resolved)))}))
        palette-vars))

(defn- palette-colors
  "The distinct resolved colors of a palette.  Duplicates such as two white
  tokens collapse, which is the point: the set answers \"does high contrast
  paint the same colors as light?\""
  [theme]
  (->> (palette-tokens theme)
       (keep :color)
       (map #(select-keys % [:r :g :b :a]))
       set))

(defn- skipped-css-declarations [css]
  (for [{:keys [selector body]} (css-blocks css)
        [property value] (declarations body)
        :let [reason (cond
                       (string/includes? value "color-mix(")
                       "color-mix"

                       (re-find #"(?i)(linear|radial|conic)-gradient\(" value)
                       "gradient"

                       (string/includes? value "url(")
                       "image background")]
        :when reason]
    {:selector (string/trim selector)
     :property property
     :value value
     :reason reason
     :probe nil}))

(deftest palette-themes-define-the-required-variables
  (let [themes (theme-vars (css-source))]
    (doseq [theme all-themes]
      (testing (str theme " theme")
        (doseq [variable palette-vars]
          (is (string? (get-in themes [theme variable]))
              (str theme " is missing " variable)))))))

(deftest high-contrast-declares-its-own-palette
  (testing "the data-contrast=high block carries real color values"
    (let [high-block (:high-block (theme-vars (css-source)))]
      (is (not (empty? high-block))
          "html[data-contrast=\"high\"] must declare variables, not only color-scheme")
      (doseq [variable palette-vars]
        (is (string? (get high-block variable))
            (str "high contrast must declare its own " variable)))))
  (testing "high contrast resolves differently from light"
    (let [themes (theme-vars (css-source))
          tokens (fn [theme] (palette-tokens (get themes theme)))
          light-tokens (tokens :light)
          high-tokens (tokens :high-contrast)]
      (is (every? :color light-tokens)
          (str "every light token must resolve: "
               (pr-str (remove :color light-tokens))))
      (is (every? :color high-tokens)
          (str "every high-contrast token must resolve: "
               (pr-str (remove :color high-tokens))))
      (let [light-colors (palette-colors (:light themes))
            high-colors (palette-colors (:high-contrast themes))]
        (println (format "PALETTE-COLORS theme=light count=%d %s"
                         (count light-colors) (pr-str (sort-by str light-colors))))
        (println (format "PALETTE-COLORS theme=high-contrast count=%d %s"
                         (count high-colors) (pr-str (sort-by str high-colors))))
        (is (not= light-colors high-colors)
            "the high-contrast palette must differ from the light palette")
        (is (not= "#ffffff" (:resolved (resolve-vars (:light themes)
                                                     "--wb-ink")))
            "light ink is not white; a shared all-white set would prove nothing")
        (doseq [{:keys [var]} high-tokens
                :let [raw (get-in themes [:high-contrast var])
                      light-raw (get-in themes [:light var])]
                :when (and (string? raw) (string? light-raw))]
          (is (or (not= raw light-raw)
                  (= var "--wb-surface")
                  (= var "--wb-accent-ink")
                  (= var "--wb-bg"))
              (str var " must not repeat the light value unless it is a "
                   "deliberately shared white/grey token (" light-raw ")")))))))

(deftest banners-use-solid-resolvable-backgrounds
  (let [css (probe-css)
        themes (theme-vars css)]
    (doseq [banner [".hc-banner-error" ".hc-banner-warning" ".hc-banner-info"]]
      (let [raw (declaration-value css banner "background")]
        (is (some? raw) (str banner " must declare a background"))
        (is (not (string/includes? (str raw) "color-mix("))
            (str banner " must not use color-mix(); a static test cannot resolve it"))
        (doseq [theme all-themes]
          (let [bg (resolved-color (get themes theme) css
                                   {:rule banner :property "background"})]
            (is (= :ok (:status bg))
                (format "%s/%s background must resolve to a solid color: %s"
                        theme banner (pr-str bg)))
            (when (= :ok (:status bg))
              (is (opaque (:color bg))
                  (format "%s/%s background must be opaque: %s"
                          theme banner (pr-str bg))))))))))

(deftest color-parser-and-var-resolution-cover-the-required-forms
  (let [themes (theme-vars (css-source))]
    (is (= {:r 255.0 :g 255.0 :b 255.0 :a 1.0}
           (parse-color "#fff")))
    (is (= {:r 1.0 :g 2.0 :b 3.0 :a 1.0}
           (parse-color "rgb(1, 2, 3)")))
    (is (= 0.5 (:a (parse-color "rgba(0, 0, 0, 0.5)"))))
    (is (= "#18202a"
           (:value (resolve-vars (:light themes) "var(--wb-ink)"))))
    (is (= "#abc"
           (:value (resolve-vars (:light themes)
                                 "var(--missing-token, #abc)"))))
    (is (= "#18202a"
           (:value (resolve-vars (:light themes)
                                 "var(--missing-token, var(--wb-ink))"))))
    (is (= :skipped
           (:status (resolve-vars (:light themes)
                                  "color-mix(in srgb, #fff 50%, #000)")))))
  (let [background (parse-color "#ffffff")
        foreground (parse-color "rgba(0, 0, 0, 0.5)")
        ratio (contrast-ratio foreground background)]
    (is (< ratio 4.5)
        "50% black text over white is below normal-text contrast")
    (is (> ratio 3.0)
        "alpha compositing still distinguishes the result from black")))

(deftest required-contrast-pairs-meet-wcag
  (let [css (probe-css)
        themes (theme-vars css)]
    (doseq [theme all-themes
            probe (probes-for-theme theme)]
      (let [fg (resolved-color (get themes theme) css (:fg probe))
            bg (resolved-color (get themes theme) css (:bg probe))]
        (if (or (= :skipped (:status fg))
                (= :skipped (:status bg)))
          (is false
              (format "required probe %s/%s was skipped: fg=%s bg=%s"
                      theme (:id probe)
                      (pr-str fg) (pr-str bg)))
          (if-not (opaque (:color bg))
            (is false
                (format "required probe %s/%s has translucent background: %s"
                        theme (:id probe) (pr-str bg)))
            (let [ratio (contrast-ratio (:color fg) (:color bg))
                  minimum (:minimum probe)]
              (println
               (format "CONTRAST theme=%s probe=%s source=%s vs %s fg=%s bg=%s ratio=%.2f min=%.1f"
                       (name theme)
                       (name (:id probe))
                       (source-label (:fg probe))
                       (source-label (:bg probe))
                       (or (:resolved fg) (:raw fg))
                       (or (:resolved bg) (:raw bg))
                       ratio
                       minimum))
              (is (>= (+ ratio 1.0e-9) minimum)
                  (format
                   "selector/variable=%s theme=%s source=%s vs %s foreground=%s background=%s ratio=%.2f required=%.1f"
                   (:label probe)
                   (name theme)
                   (source-label (:fg probe))
                   (source-label (:bg probe))
                   (or (:resolved fg) (:raw fg))
                   (or (:resolved bg) (:raw bg))
                   ratio
                   minimum)))))))))

(deftest skipped-items-never-hide-required-probes
  (let [css (probe-css)
        themes (theme-vars css)
        probe-skips
        (for [theme all-themes
              probe (probes-for-theme theme)
              :let [fg (resolved-color (get themes theme) css (:fg probe))
                    bg (resolved-color (get themes theme) css (:bg probe))]
              :when (or (= :skipped (:status fg))
                        (= :skipped (:status bg)))]
          {:selector (:label probe)
           :property (name (:id probe))
           :value (pr-str (if (= :skipped (:status fg)) fg bg))
           :reason (or (:reason (when (= :skipped (:status fg)) fg))
                       (:reason (when (= :skipped (:status bg)) bg)))
           :probe (:id probe)})
        skipped (vec (concat (skipped-css-declarations css) probe-skips))
        required (into required-probe-ids high-contrast-probe-ids)]
    (println (format "SKIPPED count=%d" (count skipped)))
    (doseq [item skipped]
      (println (format "SKIP selector=%s property=%s value=%s reason=%s"
                       (:selector item)
                       (:property item)
                       (:value item)
                       (:reason item))))
    (is (not-any? #(contains? required (:probe %)) skipped)
        (str "a required probe was skipped: "
             (pr-str (filter #(contains? required (:probe %))
                             skipped))))))
