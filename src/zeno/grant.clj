(ns zeno.grant
  "Zeno core — the capability grant.

  A spawned agent's ONLY capability is `(eval <clojure>)` against a
  deny-by-default SCI image that holds exactly the vocabulary the domain grants —
  nothing else is callable (no fs, no shell, no network) except through the
  granted fns. The domain supplies:
    :vocab     map of name-string -> fn         (the callable verbs)
    :docs      map of name-string -> one-liner  (shown by (tools))
    :ctx-info  map returned by (context)        (role, branch, whatever)

  This is the capability boundary: adding a verb is a code edit to the vocab, not
  prompt engineering. Kept domain-agnostic so any Zeno role can build a grant."
  (:require [sci.core :as sci]))

(defn build
  "Build an SCI context exposing the granted vocab plus (context) and (tools)."
  [{:keys [vocab docs ctx-info]}]
  (let [ns-map (into {'context (fn [] (or ctx-info {}))
                      'tools   (fn [] (into ["(context) — your role and context"
                                             "(tools) — the verbs you may call"]
                                            (map (fn [[n _]] (get docs n (str "(" n ")"))) vocab)))}
                     (for [[n f] vocab] [(symbol n) f]))]
    (sci/init {:namespaces {'user ns-map}})))

(defn eval-str
  "Evaluate agent-supplied Clojure `code` against grant context `ctx`."
  [ctx code]
  (sci/eval-string* ctx code))
