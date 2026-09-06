(ns researcher.task
  "The single active worker task in the living image. The WORKER gateway reads this
   to branch-scope its write grant (put-concept!/put-reference! commit here).
   One worker at a time (WIP=1): set on run, cleared on completion.")

(def trace
  "Per-run log of the worker's eval calls: [{:code :ok? :result :ms}]. The
   gateway appends each call; the worker posts it to the issue for human review."
  (atom []))

(def current
  "nil, or {:profile :worker :wiki-repo <clone-path> :branch <str> :issue <n>}."
  (atom nil))
