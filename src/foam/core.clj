(ns foam.core
  (:require [neo4j-clj.core :as db])
  (:require [crypto.random] [clojure.data.json :as json] [compojure.core :refer :all] [compojure.route :as route])  (:import (java.net URI)))(def db-url
                                                                                                                                               (new URI "bolt://localhost:7687"))

(def local-db
  (db/connect db-url "neo4j" "admin"))

(defn -main []
  (println "Starting Server"))

(db/defquery create-page-query 
  "CREATE (p:page {title: $title, id: $id})")

(db/defquery create-block-query
  "CREATE (b:block {id: $id, text: $text})")

(db/defquery create-page-contains-relation-for-block
  "MATCH (p:page {id: $pid}) MATCH (b:block {id: $bid}) create (p)-[:CONTAINS{position: $position}]->(b)")

(db/defquery get-last-page-position-query
  "MATCH (p:page {id: $pid}) MATCH (p)-[r:CONTAINS]->() RETURN COALESCE(max(r.position), -1) as position")

(db/defquery get-last-block-position-query
  "MATCH (b:block {id: $bid}) MATCH (b)-[r:CONTAINS]->() RETURN COALESCE(max(r.position), -1) as position")

(db/defquery create-block-contains-relation-for-block 
  "MATCH (b1:block {id: $bid1}) MATCH (b2:block {id: $bid2}) create (b1)-[:CONTAINS{position: $position}]->(b2)")

(db/defquery create-block-links-to-page-relation
  "MATCH (p:page {title: $ptitle}) MATCH (b:block {id: $bid}) create (b)-[:LINKS_TO]->(p)")

(db/defquery get-block-links-to-page-relations
  "MATCH (b:block {id: $bid}) MATCH (b)-[r:LINKS_TO]->(p:page) return p.title as title")

(db/defquery remove-block-links-to-page-relations
  "MATCH (b:block {id: $bid}) MATCH (b)-[r:LINKS_TO]->(p:page) where not p.title in $remaininglinks delete r")
  
(db/defquery get-blocks-for-page-query
  "MATCH (p:page)-[r:CONTAINS]->(b1:block) where p.id = $pid or p.title = $pid return p, b1 order by r.position")

(db/defquery get-blocks-for-block-query
  "MATCH (b:block {id: $bid})-[r:CONTAINS]->(b1:block) return b, b1 order by r.position")

(db/defquery move-block-up-one-position
  "MATCH (:page)-[r:CONTAINS]->(b:block {id: $bid}) MATCH (p)-[rabove:CONTAINS]->(:block) WHERE rabove.position = r.position -1 SET r.position = rabove.position, rabove.position = rabove.position + 1")

(db/defquery move-block-down-one-position
  "MATCH (:page)-[r:CONTAINS]->(b:block {id: $bid}) MATCH (p)-[rbelow:CONTAINS]->(:block) WHERE rbelow.position = r.position + 1 SET r.position = rbelow.position, rbelow.position = rbelow.position - 1")

(db/defquery find-page-by-title-query
  "MATCH (p:page {title: $title}) return p")

(db/defquery edit-block-query
  "MATCH (b:block {id: $bid}) SET b.text = $text")


;this should be executed inside another transaction
(defn get-last-page-position [tx page-id] (:position (first (get-last-page-position-query tx {:pid page-id}))))

(defn get-last-block-position [tx block-id] (:position (first (get-last-block-position-query tx {:bid block-id}))))

(defn seed-page-with-empty-block
  ([tx page-id]
   (def block-id (str (crypto.random/hex 10)))
   (create-block-query tx {:id block-id :text ""})
   (create-page-contains-relation-for-block tx {:pid page-id :bid block-id :position 0})))

(defn create-page 
  ([tx title]
   (when (= (find-page-by-title-query tx {:title title}) ())
     (def new-page-id (str (crypto.random/hex 10)))
     (create-page-query tx {:title title :id new-page-id})
     (seed-page-with-empty-block tx new-page-id)))
  ([title] (db/with-transaction local-db tx (create-page tx title))))

(defn update-relations-for-block [tx block-id text]
  ;check to see if our block-to-be links to any pages. if so, find / create that page and create the links_to relation
  (def pages-to-lookup (map second (re-seq #"\[\[(.*?)\]\]" text)))
  (doseq [page pages-to-lookup]
    (do 
      (create-page tx page)
      (create-block-links-to-page-relation tx {:bid block-id :ptitle page})))
  (remove-block-links-to-page-relations tx {:bid block-id :remaininglinks (vec pages-to-lookup)}))

(defn add-new-block-to-block 
  ([tx original-block-id text]
   (def new-block-id
     (str (crypto.random/hex 10)))
   (def position
     (get-last-block-position tx original-block-id))      
   (create-block-query tx {:id new-block-id :text text})
   (create-block-contains-relation-for-block tx {:bid1 original-block-id :bid2 new-block-id :position (inc position)})
   (update-relations-for-block tx new-block-id text))
  ([original-block-id text] (db/with-transaction local-db tx (add-new-block-to-block tx original-block-id text))))

(defn add-new-block-to-page
  ([tx page-id text]
   (def block-id
     (str (crypto.random/hex 10)))
   (def position
     (get-last-page-position tx page-id))
   (create-block-query tx {:id block-id :text text})
   (update-relations-for-block tx block-id text)
   (create-page-contains-relation-for-block tx {:pid page-id :bid block-id :position (inc position)}))
  ([page-id text] (db/with-transaction local-db tx (add-new-block-to-page tx page-id text))))
                                     
(defn edit-block 
  ([tx block-id text]
   (edit-block-query tx {:bid block-id :text text})
   (update-relations-for-block tx block-id text))
  ([block-id text] (db/with-transaction local-db tx (edit-block tx block-id text))))

(defn move-block-up
  [block-id] 
  (db/with-transaction local-db tx (move-block-up-one-position tx {:bid block-id})))

(defn move-block-down
  [block-id] 
  (db/with-transaction local-db tx (move-block-down-one-position tx {:bid block-id})))

(defn run-user-query [text user-query]
  (db/with-transaction local-db tx
    (db/defquery uq user-query)
    (def uq-result (uq tx))
    (clojure.string/replace text (str "{{code:cypher" user-query "}}") (json/write-str uq-result))))

(defn print-block-and-children [block level]
  ;before printing, determine if we need to run a cypher query
  (def user-queries-to-run (map second (re-seq #"\{\{code:cypher(.*?)\}\}" (:text (:b1 block)))))
  (def final-string (if (not-empty user-queries-to-run) (do ;run each query, then replace the whole block with the result
                                                          (reduce 
                                                            run-user-query
                                                            (:text (:b1 block))
                                                            user-queries-to-run)) ;replace the text with user queries
                      (:text (:b1 block)))) ;default value of the original text
  (println (str (:id (:b1 block)) (repeat level "*") final-string))
  (let [blocks (db/with-transaction local-db tx (seq (get-blocks-for-block-query tx {:bid (:id (:b1 block))})))]
    (doseq [child blocks]
      (print-block-and-children child (inc level)))))

(defn show-page [page-title]
  (def blocks (db/with-transaction local-db tx 
                (create-page tx page-title)
                (seq (get-blocks-for-page-query tx {:pid page-title}))))
  (println (:title (:p (first blocks))))
  (doseq [block blocks]
    (print-block-and-children block 1)))
  
(defn daily-notes []
  (def date (.format (java.text.SimpleDateFormat. "MMM d, yyyy") (new java.util.Date)))
  (show-page date))

(defroutes app
  (GET "/" [] "<h1>Hello World</h1>")
  (route/not-found "<h1>Page not found</h1>"))

(run-jetty app {:port 3000 :join? false})