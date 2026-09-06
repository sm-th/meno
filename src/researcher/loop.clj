(ns researcher.loop
  "Minimal supervised step loop (input -> handle -> record). Steps are plain
   fns in a map so they can be redefined live; a crashing step is isolated and
   the context passes through. A single tick for now; a real driver would keep
   ticking on new commits / ready issues.")

(defn- supervise [label f ctx]
  (try
    (f ctx)
    (catch Throwable t
      (println (str "!! step " label " error: " (.getMessage t)))
      ctx)))

(defn tick [{:keys [input handle record]} ctx]
  (->> ctx
       (supervise "input"  input)
       (supervise "handle" handle)
       (supervise "record" record)))
