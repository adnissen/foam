(defproject foam "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies [ [org.clojure/clojure "1.10.1"] [org.neo4j.test/neo4j-harness "4.0.5"] [gorillalabs/neo4j-clj "4.0.1"] [crypto-random/crypto-random "1.2.0"] [org.clojure/data.json "1.0.0"] [compojure "1.6.1"] [ring/ring-jetty-adapter "1.8.1"]]    :plugins [ [lein-ring "0.12.5"]] 
  :ring {:handler foam.core/app}
  :main ^:skip-aot foam.core)
