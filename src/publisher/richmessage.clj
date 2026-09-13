(ns publisher.richmessage
  "Build a Telegram Rich Message (Bot API 10.1) from an English post: the title
   as a size-1 heading, the body as content blocks (paragraphs, headings, lists,
   quotes, code — with inline links/bold/code/italic), and a footer block holding
   the site link as visible linked text. Ported from the earlier telegram
   publisher; no images."
  (:require [clojure.string :as str]))

(def ^:private inline-re
  ;; link | bold | code | italic  (groups: 1-2 link text/url, 3 bold, 4 code, 5 italic)
  #"\[([^\]]+)\]\(([^)]+)\)|\*\*([^*]+)\*\*|`([^`]+)`|\*([^*]+)\*")

(defn- inline-rich
  "Inline Markdown -> a RichText value: a plain string when there's no formatting,
   else a vector of strings and entity maps."
  [text]
  (let [m (re-matcher inline-re text)]
    (loop [out [] pos 0]
      (if (.find m)
        (let [s   (.start m)
              e   (.end m)
              out (cond-> out (> s pos) (conj (subs text pos s)))
              ent (cond
                    (.group m 1) {:type "url"    :text (.group m 1) :url (.group m 2)}
                    (.group m 3) {:type "bold"   :text (.group m 3)}
                    (.group m 4) {:type "code"   :text (.group m 4)}
                    :else        {:type "italic" :text (.group m 5)})]
          (recur (conj out ent) e))
        (let [out (cond-> out (< pos (count text)) (conj (subs text pos)))]
          (cond
            (empty? out)                                    text
            (and (= 1 (count out)) (string? (first out)))   (first out)
            :else                                           out))))))

(defn- para [text] {:type "paragraph" :text (inline-rich text)})

(defn- flush-buf [out buf]
  (let [text (str/trim (str/join " " (map str/trim buf)))]
    (if (str/blank? text) out (conj out (para text)))))

(def ^:private head-re  #"^(#{1,6})\s+(.*)$")
(def ^:private quote-re #"^>\s?(.*)$")
(def ^:private list-re  #"^\s*(?:[-*]|\d+\.)\s+(.*)$")

(defn- content-blocks
  "Parse the Markdown body into rich blocks."
  [body]
  (let [lines (str/split-lines body)
        n     (count lines)]
    (loop [i 0 buf [] out []]
      (if (>= i n)
        (flush-buf out buf)
        (let [line (nth lines i)]
          (cond
            (str/blank? line)
            (recur (inc i) [] (flush-buf out buf))

            (str/starts-with? (str/trim line) "```")
            (let [out  (flush-buf out buf)
                  lang (str/trim (subs (str/trim line) 3))
                  [code j] (loop [code [] j (inc i)]
                             (if (and (< j n) (not (str/starts-with? (str/trim (nth lines j)) "```")))
                               (recur (conj code (nth lines j)) (inc j))
                               [code (inc j)]))
                  block (cond-> {:type "pre" :text (str/join "\n" code)}
                          (seq lang) (assoc :language lang))]
              (recur j [] (conj out block)))

            (re-find head-re line)
            (let [[_ hashes t] (re-find head-re line)]
              (recur (inc i) []
                     (conj (flush-buf out buf)
                           {:type "heading" :text (inline-rich (str/trim t))
                            :size (min 6 (+ (count hashes) 2))})))

            (re-find quote-re line)
            (let [out (flush-buf out buf)
                  [q j] (loop [q [] j i]
                          (if (and (< j n) (re-find quote-re (nth lines j)))
                            (recur (conj q (second (re-find quote-re (nth lines j)))) (inc j))
                            [q j]))]
              (recur j [] (conj out {:type "blockquote" :blocks [(para (str/trim (str/join " " q)))]})))

            (re-find list-re line)
            (let [out (flush-buf out buf)
                  [items j] (loop [items [] j i]
                              (if (and (< j n) (re-find list-re (nth lines j)))
                                (recur (conj items (str/trim (second (re-find list-re (nth lines j))))) (inc j))
                                [items j]))]
              (recur j [] (conj out {:type "list" :items (mapv (fn [it] {:blocks [(para it)]}) items)})))

            :else
            (recur (inc i) (conj buf line) out)))))))

(defn build
  "Rich message payload {:blocks [...]}: heading(title) + content + footer(site)."
  [title body site-url]
  (let [site-disp (str/replace (str site-url) #"^https?://" "")]
    {:blocks (vec (concat [{:type "heading" :text title :size 1}]
                          (content-blocks body)
                          [{:type "footer"
                            :text [{:type "url" :text site-disp :url site-url}]}]))}))
