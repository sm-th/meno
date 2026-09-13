;; ~/.zeno/init.clj — the instance program, loaded by `nix run …#zeno`
;; (like ~/.emacs.d/init.el). zeno has put this project's src and machine
;; packages on the classpath; the config dir is System property `zeno.home`.
(require 'boot)

(if (System/getenv "ZULIP_OWNER_API_KEY")
  ;; configured -> provision identities and run
  (boot/-main)
  ;; not configured yet -> load and explain, don't crash
  (let [home (System/getProperty "zeno.home")
        inst (boot/load-instance (str home "/instance.edn"))]
    (println "zeno: instance loaded but NOT started (owner creds absent).")
    (println "  machines:" (vec (keys (:machines inst))))
    (println "  bootstrap:  cd" home "&& nix develop -c clojure -M -m botfather")
    (println "  then run:   cd" home "&& secretspec run -- nix run github:reflection-dev/zeno")))
