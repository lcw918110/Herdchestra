(ns hc.client.appearance
  "Browser application of appearance/accessibility preferences.

  This namespace is deliberately small: the pure model and view live in
  `hc.client.appearance.model` and `hc.client.appearance.view`.  A host calls
  `apply!` after a state change; the function only sets the document
  attributes and the font-scale variable.

  The palette and the high-contrast / reduced-motion rules live in
  `resources/public/css/workbench.css`, not here.  A second copy injected at
  runtime would drift from the stylesheet that the contrast test parses, so
  the tested values and the painted values stay the same file.  This
  namespace never writes localStorage or Fulcro state."
  (:require [hc.client.appearance.model :as model]
            [hc.client.appearance.view :as view]))


(defn apply!
  "Apply normalized STATE to the document root.  Safe to call repeatedly."
  [state]
  (when (exists? js/document)
    (let [state (model/normalize state)
          root (.-documentElement js/document)]
      (set! (.. root -dataset -theme) (model/theme-attribute (:theme state)))
      (set! (.. root -dataset -contrast) (name (:contrast state)))
      (set! (.. root -dataset -reducedMotion)
            (case (:reduced-motion state)
              :reduce "true"
              :no-preference "false"
              "auto"))
      (set! (.. root -dataset -language) (name (:language state)))
      (set! (.. root -dataset -background) (name (:background state)))
      (set! (.. root -dataset -bubble) (name (:bubble state)))
      (.setProperty (.-style root) "--wb-font-scale"
                    (str (/ (:font-scale state) 100))))))

(defn from-db
  [db]
  (model/from-db db))

(defn to-db
  [state]
  (model/to-db state))

(defn to-ui
  [state]
  (model/to-ui state))

(defn settings-tree
  [state]
  (view/settings-tree state))
