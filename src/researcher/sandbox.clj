(ns researcher.sandbox
  "Build a real `msb run` invocation (microsandbox 0.6.16) from a config profile.
   Dry-run: we only render/print the command; we never execute it here."
  (:require [researcher.config :as config]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(defn msb-present? []
  (or (try (zero? (:exit (sh "sh" "-c" "command -v msb >/dev/null 2>&1"))) (catch Throwable _ false))
      (.exists (java.io.File. (str (System/getProperty "user.home") "/.microsandbox/bin/msb")))))

(defn launch-tokens
  "Return the argv (vector of strings) for `msb run` under a profile."
  [cfg profile-key cmd]
  (let [prof  (get-in cfg [:sandbox :profiles profile-key])
        image (get-in cfg [:sandbox :image])
        toks  (atom ["msb" "run" image "--name" (str "researcher-" (name profile-key))])
        add!  (fn [& xs] (swap! toks into xs))]
    (doseq [{:keys [src dst ro]} (:mounts prof)]
      (add! "-v" (str (config/expand src) ":" dst (when ro ":ro"))))
    (when-let [cw (:copy-wiki prof)]
      (add! "--copy-dir" cw))
    (when-let [d (:net-default prof)]
      (add! "--net-default-egress" d))
    (doseq [{:keys [host ports]} (:allow prof)]
      (add! "--net-rule" (str "allow@" host (when ports (str ":" ports)))))
    (doseq [{:keys [env hosts]} (:secrets prof)]
      (add! "--secret" (str env "@" (str/join "," hosts))))
    (add! "--on-secret-violation" "block-and-terminate")
    (when-let [x (:max-duration prof)] (add! "--max-duration" x))
    (when-let [x (:idle-timeout prof)] (add! "--idle-timeout" x))
    (add! "--entrypoint" "/entrypoint")
    (into (conj @toks "--") cmd)))

(defn render [toks]
  ;; one flag group per line for readability
  (-> (str/join " " toks) (str/replace " --" " \\\n    --")))

(defn print-launch [cfg profile-key cmd]
  (let [bar (apply str (repeat 66 \=))]
    (println bar)
    (println (str "MICROSANDBOX LAUNCH  \u25B8 profile " (name profile-key)
                  "   (dry-run: not executed; msb present: " (msb-present?) ")"))
    (println (apply str (repeat 66 \-)))
    (println (render (launch-tokens cfg profile-key cmd)))
    (println)))
