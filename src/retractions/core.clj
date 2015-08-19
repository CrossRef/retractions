(ns retractions.core
  (:gen-class)
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.xml :as xml]
            [clojure.data.json :as json]
            [clojure.data.csv :as csv]
            [clojure.zip :as zip]
            [clojure.data.zip :refer [children children-auto]]
            [clojure.data.zip.xml :refer [xml-> xml1-> text]]
            [clojure.core.async :as async]
            [clojure.set :refer [intersection]]
            [clojure.java.jdbc :as j]
            [org.httpkit.client :as http]))

(defn normalize-doi [doi]
  (->> doi
       str/lower-case))

;; PMID to DOI
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def mapping-db {:subprotocol "sqlite" :subname "mapping.sqlite"})

(defn pmid->doi [pmid]
  (when-let [doi-uri (-> mapping-db
                         (j/query ["select doi from mapping where pmid = ?" pmid]
                                  :row-fn :doi)
                         first)]
    (-> doi-uri
        (str/replace #"\Ahttp://dx.doi.org/" "")
        normalize-doi)))

;; Get some retraction data
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn crossref-retractions []
  (let [{:keys [body]}
        @(http/get "http://api.crossref.org/v1/works"
                   {:query-params {:filter "update-type:retraction"
                                   :rows 1000}})
        records (-> body
                    (json/read-str :key-fn keyword)
                    (get-in [:message :items]))]
    (->> records
         (mapcat :update-to)
         (map :DOI))))

(defn pubmed-retractions []
  (->> (-> "pubmed-retraction-search-04-08-2015.txt" slurp (str/split #"\n"))
       (map pmid->doi)
       (filter (complement empty?))))

(def test-retractions ["10.1097/00000539-200110000-00036" "10.2486/indhealth.44.101"])

;; Check CrossRef XML for citations to retracted DOIs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
    
(defn xml-files [f]
  (->> f
       io/file
       file-seq
       (filter #(and (.isFile %) (.endsWith (.getPath %) ".xml")))))

(defn record->doi-citation-info [record]
  [(xml1-> record :crossref children-auto children-auto :doi_data :doi text)
   (->> (xml-> record :crossref children-auto children-auto :citation_list :citation :doi text)
        (filter (complement nil?))
        (map normalize-doi))])

(defn xml-file->doi-citation-infos [f]
  (with-open [rdr (io/reader f)]
    (let [doc (-> rdr xml/parse zip/xml-zip)
          records (xml-> doc
                         :ListRecords :record :metadata
                         :crossref_result :query_result
                         :body :crossref_metadata :doi_record)]
      (->> records
           (map record->doi-citation-info)
           (filter #(not (nil? (first %))))))))

(defn retracted-citations [retracted-dois doi-citation-info]
  (let [citing-doi (first doi-citation-info)
        cited-dois (set (second doi-citation-info))]
    (intersection retracted-dois cited-dois)))

(defn update-result [result citing-doi cited-retracted-dois]
  (reduce #(update-in %1 [%2]
                      (fn [dois]
                        (if (nil? dois)
                          (set [citing-doi])
                          (conj dois citing-doi))))
          result
          cited-retracted-dois))
          
;; Execution flow
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn process-files [file-chan citation-chan]
  (dotimes [_ 10]
    (async/go-loop [f (async/<! file-chan)]
      (try
        (doseq [doi (xml-file->doi-citation-infos f)]
          (async/>! citation-chan doi))
        (catch Throwable t
          (println f t)))
      (recur (async/<! file-chan)))))

(defn process-citations [citation-chan count-atom retracted-dois-set result]
  (dotimes [_ 5]
    (async/go-loop [doi-citation-info (async/<! citation-chan)]
      (try
        (let [cited-retracted-dois (retracted-citations retracted-dois-set
                                                        doi-citation-info)]
          (when-not (empty? cited-retracted-dois)
            (swap! result
                   update-result
                   (first doi-citation-info)
                   cited-retracted-dois)))
        (swap! count-atom inc)
        (catch Throwable t
          (println (first doi-citation-info) t)))
      (recur (async/<! citation-chan)))))

(defn create-work-flow [retracted-dois]
  (let [f {:count (atom 0)
           :result (atom {})
           :retracted-dois (set retracted-dois)
           :file-chan (async/chan (async/buffer 1000))
           :citation-chan (async/chan (async/buffer 10000))}]
    (process-files (:file-chan f) (:citation-chan f))
    (process-citations (:citation-chan f)
                       (:count f)
                       (:retracted-dois f)
                       (:result f))
    f))
        
(defn run-directory [f flow]
  (doseq [ff (-> f xml-files)]
    (async/>!! (:file-chan flow) ff)))

;; Output
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; [doi [ci..]]

(defn ->csv [f results]
  (let [lines (map #(concat [(first %)] (second %)) results)
        sorted-lines (reverse (sort-by count lines))]
    (with-open [ff (io/writer f)]
      (csv/write-csv ff sorted-lines))))

(defn cr-pubmed-diff-article-titles
  "Names of retracted articles known by CrossRef but not PubMed"
  []
  (map
   #(-> (str "http://api.crossref.org/v1/works/" %)
        (http/get )
        deref
        :body
        (json/read-str :key-fn keyword)
        (get-in [:message :title 0]))
   (clojure.set/difference
    (set (crossref-retractions))
    (set (pubmed-retractions)))))

(defn add-cr-citation-counts [results-file citation-counts-file]
  (let [results (read-string (slurp results-file))
        citation-counts (drop 1 (-> citation-counts-file slurp
                                    (str/replace #"\r" "\n")
                                    csv/read-csv))]
    (map #(concat % [(count (get results (first %)))]) citation-counts)))                             

;; (add-cr-citation-counts "pubmed-results-04-08-2015.edn" "retracted-pubmed-dois-with-wos-citation-numbers.csv")
