(ns zeno.loop
  "Zeno core — the supervised step loop.

  A loop is an ordered seq of [label step-fn]; each step takes the context map and
  returns it. Steps are plain fns so they can be redefined live in the image; a
  crashing step is isolated (logged, skipped) and the context passes through, so
  one bad step never kills the loop. Domain-agnostic: the researcher (meno) layer
  supplies the actual steps.")

(defn- supervise [label f ctx]
  (try
    (f ctx)
    (catch Throwable t
      (println (str "!! zeno step " label " error: " (.getMessage t)))
      ctx)))

(defn tick
  "Run one pass of `steps` (seq of [label fn]) over `ctx`, returning the new ctx."
  [steps ctx]
  (reduce (fn [c [label f]] (supervise (name label) f c)) ctx steps))
