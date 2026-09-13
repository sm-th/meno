;; ~/.zeno/init.clj — the instance program, loaded by `nix run …#zeno`
;; (like ~/.emacs.d/init.el). zeno has already put this project's src and machine
;; packages on the classpath; the config dir is System property `zeno.home`.
(require 'boot)

(let [home (System/getProperty "zeno.home")
      inst (boot/load-instance (str home "/instance.edn"))]
  (println "zeno: instance loaded from" home)
  (println "  machines:" (vec (keys (:machines inst))))
  (println "  → (boot/-main) provisions identities and runs (needs ZULIP_OWNER_* + per-machine creds)"))
