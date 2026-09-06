(ns researcher.reader
  "Readable-text extraction for a URL. Default: Jina Reader (r.jina.ai) — clean
   markdown, no key, handles JS. (Linkwarden's own readability archive is an
   alternative once its async preservation completes.)"
  (:require [researcher.http :as http]))

(defn readable [url]
  (let [{:keys [status body]}
        (http/json-request {:method :get :url (str "https://r.jina.ai/" url) :timeout 30})]
    (when (= 200 status)
      (if (string? body) body (str body)))))
