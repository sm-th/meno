;; ~/.zeno/init.clj — the instance program, loaded by `nix run …#zeno`
;; (like ~/.emacs.d/init.el). It only DECLARES what to run: the loop and the
;; daemon are zeno's job (zeno.loop/start!, called by zeno.main, runs registered
;; processes in BOTH the interactive and the --daemon modes). No threads here.
(require 'boot 'zeno.loop)

(let [home (System/getProperty "zeno.home")]
  (if (System/getenv "ZULIP_OWNER_API_KEY")
    (let [{:keys [poll-ms step]} (boot/setup!)]
      (zeno.loop/every :publisher poll-ms step)
      (println "zeno: publisher scheduled every" poll-ms "ms"
               "— new #blog posts publish, then go to the researcher"))
    (println (str "zeno: instance loaded but NOT started (owner creds absent).\n"
                  "  bootstrap:  cd " home " && nix develop github:reflection-dev/zeno -c clojure -M -m botfather\n"
                  "  then run:   nix run github:reflection-dev/zeno"))))
