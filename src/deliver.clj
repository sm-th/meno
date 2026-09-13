(ns deliver
  "Resolve a machine's declared accesses to concrete secret values and produce an
   injection plan. Secrets are per-machine: looked up as <MACHINE>_<SECRET>, with
   a flat <SECRET> fallback for the transition. The machine always sees the
   canonical name (GH_TOKEN, …) — prefixes never leak into machine code.

   Two delivery shapes:
     - env-map      : trusted host machine — every value as plain env.
     - sandbox-spec : sandboxed agent — non-secret values as env, each secret
                      delivered network-bound (msb --secret ENV@HOST) toward its
                      allowed host only, plus an egress allowlist. Values never
                      go on the argv and never hit VM disk."
  (:require [clojure.string :as str]))

(defn- env [k] (System/getenv k))

(defn- prefix [machine]
  (-> (name machine) str/upper-case (str/replace "-" "_")))

(defn resolve-secret [machine secret]
  (or (env (str (prefix machine) "_" secret)) (env secret)))

(defn plan
  "Args: :machine kw, :accesses [catalog-keys], :catalog {k {:secret :hosts}},
   :identity {:email :api-key} | nil, :zulip-site, :zulip-host.
   Returns {:secrets [{:env :value :hosts}] :plain {ENV VAL} :egress #{hosts}}."
  [{:keys [machine accesses catalog identity zulip-site zulip-host]}]
  (let [access-secrets (for [a accesses
                             :let [{:keys [secret hosts]} (catalog a)]]
                         {:env secret :value (resolve-secret machine secret) :hosts hosts})
        secrets (cond-> (vec access-secrets)
                  identity (conj {:env "ZULIP_API_KEY" :value (:api-key identity)
                                  :hosts [zulip-host]}))
        plain   (cond-> {}
                  identity (assoc "ZULIP_EMAIL" (:email identity) "ZULIP_SITE" zulip-site))]
    {:secrets secrets
     :plain   plain
     :egress  (into #{} (mapcat :hosts) secrets)}))

(defn env-map
  "Host delivery: plain env + every secret value inlined (trusted machine)."
  [{:keys [secrets plain]}]
  (reduce (fn [m {:keys [env value]}] (assoc m env value)) (or plain {}) secrets))

(defn sandbox-spec
  "Sandbox delivery descriptor for zeno.sandbox / msb: plain env, network-bound
   secrets (value supplied via the launcher env, released only toward :host),
   and the egress allowlist. Secret VALUES are not placed here."
  [{:keys [secrets plain egress]}]
  {:env       plain
   :net-bound (mapv (fn [{:keys [env hosts]}] {:env env :host (first hosts)}) secrets)
   :egress    (vec egress)})
