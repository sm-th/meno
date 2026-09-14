;; ~/.zeno/init.clj — the instance program, loaded by `nix run …#zeno`
;; (like ~/.emacs.d/init.el). It only DECLARES what to run: boot/start-publisher!
;; provisions the bots and runs the #blog event-driven long-poll in a supervised
;; daemon thread (watched by zeno.loop; the scheduler is started by zeno.main).
(require 'boot)

(let [home (System/getProperty "zeno.home")]
  (if (System/getenv "ZULIP_OWNER_API_KEY")
    (boot/start-publisher!)
    (println (str "zeno: instance loaded but NOT started (owner creds absent).\n"
                  "  bootstrap:  cd " home " && clojure -M -m botfather\n"
                  "  then run:   nix run github:reflection-dev/zeno"))))
