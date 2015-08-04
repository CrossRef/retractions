(defproject crossref/retractions "0.1.0"
  :description "Find DOIs that cite retracted DOIs"
  :url "http://github.com/CrossRef/retractions"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/data.zip "0.1.1"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/data.csv "0.1.2"]
                 [org.clojure/java.jdbc "0.4.1"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [http-kit "2.1.18"]
                 [org.xerial/sqlite-jdbc "3.8.11"]]
  :main ^:skip-aot retractions.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
