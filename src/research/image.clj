(ns research.image
  "The researcher agent's OWN immutable image, a Clojure spec (ADR-0013): omp (the
   coding agent) plus git/openssh for clone+push, a shell, and TLS roots. Built on
   the fly via zeno.oci — no manual rebuild. Replaces the ad-hoc `zeno-agent:base`
   with a committed, self-building `researcher-agent:base` (identical packages,
   recovered from the running image's config)."
  (:require [zeno.oci :as oci]))

(defn spec
  "OCI spec for the researcher agent. Opts: :system (default aarch64-linux)."
  [{:keys [system] :or {system "aarch64-linux"}}]
  {:name     "researcher-agent"
   :tag      "base"
   :system   system
   :packages [(str "github:numtide/llm-agents.nix#packages." system ".omp")
              "git" "openssh" "coreutils" "bashInteractive" "cacert"]
   :env      {"LANG" "C.UTF-8"}
   :cmd      ["/bin/sh"]
   :workdir  "/work"})

(defn build!
  "Build the researcher image from its Clojure spec and load it into msb; returns
   the image ref (researcher-agent:base). A linux target needs a linux builder for
   uncached packages."
  ([] (build! {}))
  ([opts] (oci/build-and-load! (spec opts))))

(def ^:private image
  "Built + loaded once per instance (a delay); an unchanged rebuild is a nix
   cache-hit, so a restart is cheap and picks up any spec change."
  (delay (build!)))

(defn ensure!
  "Ensure researcher-agent:base is built and loaded into msb, on the fly — no
   manual build step. Returns the image ref. Idempotent per instance."
  [] @image)
