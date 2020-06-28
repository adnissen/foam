(ns foam.core
  (:require [neo4j-clj.core :as db])
  (:require [crypto.random] [clojure.data.json :as json] [compojure.core :refer :all] [compojure.route :as route])  (:import (java.net URI)))(def db-url
                                                                                                                                               (new URI "bolt://localhost:7687"))
(use 'ring.adapter.jetty)

(def local-db
  (db/connect db-url "neo4j" "admin"))

(defn -main []
  (println "Starting Server"))

(db/defquery create-page-query 
  "MERGE (p:page {title: $title, id: $id})")

(db/defquery create-block-query
  "MERGE (b:block {id: $id, text: $text})")

(db/defquery create-page-contains-relation-for-block
  "MATCH (p {id: $pid}) MATCH (b:block {id: $bid}) MERGE (p)-[:CONTAINS{position: $position}]->(b)")

(db/defquery get-last-page-position-query
  "MATCH (p {id: $pid}) MATCH (p)-[r:CONTAINS]->() RETURN COALESCE(max(r.position), -1) as position")

(db/defquery get-last-block-position-query
  "MATCH (b {id: $bid}) MATCH (b)-[r:CONTAINS]->() RETURN COALESCE(max(r.position), -1) as position")

(db/defquery create-block-contains-relation-for-block 
  "MATCH (b1 {id: $bid1}) MATCH (b2:block {id: $bid2}) MERGE (b1)-[:CONTAINS{position: $position}]->(b2)")

(db/defquery create-block-links-to-page-relation
  "MATCH (p:page {title: $ptitle}) MATCH (b:block {id: $bid}) merge (b)-[:LINKS_TO]->(p)")

(db/defquery get-block-links-to-page-relations
  "MATCH (b:block {id: $bid}) MATCH (b)-[r:LINKS_TO]->(p:page) return p.title as title")

(db/defquery remove-block-links-to-page-relations
  "MATCH (b:block {id: $bid}) MATCH (b)-[r:LINKS_TO]->(p:page) where not p.title in $remaininglinks delete r")
  
(db/defquery get-blocks-for-page-query
  "MATCH (p:page {id: $pid})-[r:CONTAINS]->(b1:block) return p, b1 order by r.position")

(db/defquery get-blocks-for-block-query
  "MATCH (b:block {id: $bid})-[r:CONTAINS]->(b1:block) return b, b1 order by r.position")

(db/defquery move-block-up-one-position
  "MATCH (:page)-[r:CONTAINS]->(b:block {id: $bid}) MATCH (p)-[rabove:CONTAINS]->(:block) WHERE rabove.position = r.position -1 SET r.position = rabove.position, rabove.position = rabove.position + 1")

(db/defquery move-block-down-one-position
  "MATCH (:page)-[r:CONTAINS]->(b:block {id: $bid}) MATCH (p)-[rbelow:CONTAINS]->(:block) WHERE rbelow.position = r.position + 1 SET r.position = rbelow.position, rbelow.position = rbelow.position - 1")

(db/defquery find-page-by-title-query
  "MATCH (p:page {title: $title}) return p")

(db/defquery find-page-id-by-title-query
  "MATCH (p:page {title: $title}) return p.id as id")

(db/defquery edit-block-query
  "MATCH (b:block {id: $bid}) SET b.text = $text")

(db/defquery delete-contains-relations-to-block-query
  "MATCH ()-[r:CONTAINS]->(b:block {id: $bid}) delete r")

(db/defquery move-other-blocks-up-before-delete-query
  "MATCH (parent)-[r:CONTAINS]->(b:block {id: $bid}) MATCH (parent)-[r2:CONTAINS]->() where r2.position > r.position set r2.position = r2.position - 1")

(db/defquery delete-references-to-block-query
  "MATCH ()-[r]->(b:block {id: $bid}) delete r")

(db/defquery delete-references-from-block-query
  "MATCH (b:block {id: $bid})-[r]->() delete r")

(db/defquery delete-block-query
  "MATCH (b:block {id: $bid}) delete b")

(db/defquery get-block-id-to-indent-into
  "match (parent)-[r:CONTAINS]->(b:block {id: $bid}) optional match (parent)-[r2:CONTAINS]->(b2) where r2.position = r.position - 1 optional match (grandparent) -[r3:CONTAINS]->(parent) optional match (grandparent)-[r4:CONTAINS]->(b3) where r4.position = r3.position - 1 return COALESCE(b2.id, r3.id, null) as new_parent_id")

(db/defquery get-block-id-to-unindent-into
  "match (parent)-[r:CONTAINS]->(b:block {id: $bid}) optional match (grandparent)-[r2:CONTAINS]->(parent) return COALESCE(grandparent.id, null) as new_parent_id")

(db/defquery get-number-of-fellow-root-blocks-in-page ;this will return 0 if the block passed in is not a root block
  "match (p:page)-[r:CONTAINS]->(b:block {id: $bid}) match (p)-[r2:CONTAINS]->() return COUNT(r2) as root_blocks_in_page")

(db/defquery get-unlinked-references-query
  "MATCH (parent:page {id: $pid}) MATCH (p:page)-[:CONTAINS*]->(b:block) where b.text contains parent.title and not b.text contains '[[' + parent.title + ']]' return p.id as page_id, p.title as title, b.id as block_id, b.text as text")

(db/defquery get-linked-references-query
  "MATCH (parent:page {id: $pid}) MATCH (p:page)-[:CONTAINS*]->(b:block) where b.text contains '[[' + parent.title + ']]' return p.id as page_id, p.title as title, b.id as block_id, b.text as text")

(db/defquery search-page-titles-query
  "MATCH (p:page) WHERE p.title contains $titlefragment return p.id as page_id, p.title as title")

(db/defquery search-page-titles-and-blocks-query
  "MATCH (p:page) WHERE p.title contains $searchfragment return p.id as page_id, p.title as title, null as block_id, null as text UNION ALL MATCH (parent:page)-[:CONTAINS*]->(b:block) where b.text contains $searchfragment return parent.id as page_id, parent.title as title, b.id as block_id, b.text as text")

(db/defquery rename-page-query
  "MATCH (p:page {title: $ptitle}) MATCH (b:block) where b.text contains '[[' + $ptitle + ']]' set p.title = $newtitle, b.text = replace(b.text, '[[' + $ptitle + ']]', '[[' + $newtitle + ']]')")

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
   (def existing-id (find-page-by-title-query tx {:title title}))
   (if (= existing-id ())
      (do
        (def new-page-id (str (crypto.random/hex 10)))
        (create-page-query tx {:title title :id new-page-id})
        (seed-page-with-empty-block tx new-page-id)
        new-page-id)
      (:id (:p (first existing-id)))))
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
   (create-page-contains-relation-for-block tx {:pid page-id :bid block-id :position (inc position)})
   (update-relations-for-block tx block-id text))
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

(defn delete-block [block-id]
  (db/with-transaction local-db tx
    (def root-blocks (:root_blocks_in_page (first (get-number-of-fellow-root-blocks-in-page tx {:bid block-id}))))
    (def last-block? (== root-blocks 1))
    (if (not last-block?) (do
      (move-other-blocks-up-before-delete-query tx {:bid block-id})
      (delete-references-to-block-query tx {:bid block-id})
      (delete-references-from-block-query tx {:bid block-id})
      (delete-block-query tx {:bid block-id})
    )) 
  ))

(defn indent-block [block-id]
  (db/with-transaction local-db tx 
    (def new-parent-id (:new_parent_id (first (get-block-id-to-indent-into tx {:bid block-id}))))
    (if (some? new-parent-id) (do
      (delete-contains-relations-to-block-query tx {:bid block-id})
      (def new-position (get-last-block-position tx new-parent-id))
      (create-block-contains-relation-for-block tx {:bid1 new-parent-id :bid2 block-id :position (inc new-position)})
    ))
  )
)

(defn unindent-block [block-id]
  (db/with-transaction local-db tx 
    (def new-parent-id (:new_parent_id (first (get-block-id-to-unindent-into tx {:bid block-id}))))
    (if (some? new-parent-id) (do
      (delete-contains-relations-to-block-query tx {:bid block-id})
      (def new-position (get-last-block-position tx new-parent-id))
      (create-block-contains-relation-for-block tx {:bid1 new-parent-id :bid2 block-id :position (inc new-position)})
    ))
  )
)

(defn get-unlinked-references [page-id]
  (db/with-transaction local-db tx
    (json/write-str (get-unlinked-references-query tx {:pid page-id}))))

(defn get-linked-references [page-id]
  (db/with-transaction local-db tx
    (json/write-str (get-linked-references-query tx {:pid page-id}))))

(defn search-page-titles [title-fragment]
  (db/with-transaction local-db tx
    (json/write-str (search-page-titles-query tx {:titlefragment title-fragment}))))

(defn search-page-titles-and-blocks [search-fragment]
  (db/with-transaction local-db tx
    (json/write-str (search-page-titles-and-blocks-query tx {:searchfragment search-fragment}))))

(defn rename-page [page-title new-title]
  (db/with-transaction local-db tx
    (rename-page-query tx {:ptitle page-title :newtitle new-title})))

(defn run-user-query [text user-query]
  (db/with-transaction local-db tx
    (db/defquery uq user-query)
    (def uq-result (uq tx))
    (clojure.string/replace text (str "{{code:cypher" user-query "}}") (json/write-str uq-result))))

(defn replace-page-names-with-links [text page]
  (db/with-transaction local-db tx 
    (def page-id (:id (first (find-page-id-by-title-query tx {:title page}))))
    (clojure.string/replace text page (str "<a contenteditable='false' href='/app/show/" page-id "'>" page "</a>"))))
  
(defn print-block-and-children 
  ([old-output block level]
   ;before printing, determine if we need to run a cypher query
   (def user-queries-to-run (map second (re-seq #"\{\{code:cypher(.*?)\}\}" (:text (:b1 block)))))
   (def string-after-user-queries (if (not-empty user-queries-to-run) (do ;run each query, then replace the whole block with the result
                                                                        (reduce 
                                                                          run-user-query
                                                                          (:text (:b1 block))
                                                                          user-queries-to-run)) ;replace the text with user queries
                                    (:text (:b1 block)))) ;default value of the original text
   (def page-ids-to-lookup (map second (re-seq #"\[\[(.*?)\]\]" string-after-user-queries)))
   (def string-after-page-names-replaced-with-links (reduce replace-page-names-with-links string-after-user-queries page-ids-to-lookup))  
   (def new-output (conj {:text string-after-page-names-replaced-with-links :id (:id (:b1 block)) :children []}))
   (def blocks (db/with-transaction local-db tx (seq (get-blocks-for-block-query tx {:bid (:id (:b1 block))}))))
   (if (not-empty blocks) (conj old-output (assoc new-output :children (reduce print-block-and-children [] blocks)))
     (conj old-output new-output)))   
  ([old-output block]
   (print-block-and-children old-output block 1)))


(defn show-page [page-id]
  (def blocks (db/with-transaction local-db tx 
                (seq (get-blocks-for-page-query tx {:pid page-id}))))
  (json/write-str {:pageTitle (:title (:p (first blocks))) :id (:id (:p (first blocks))) :children (reduce print-block-and-children [] blocks)}))

(defn show-or-create-page [page-name]
  (create-page page-name))
  
(defn daily-notes []
  (def date (.format (java.text.SimpleDateFormat. "MMM d, yyyy") (new java.util.Date)))
  (db/with-transaction local-db tx (def page-contents (find-page-id-by-title-query tx {:title date}))
                                   (if (= page-contents ()) (create-page date))
                                   (show-page (:id (first (find-page-id-by-title-query tx {:title date}))))))
          
(require '[ring.middleware.defaults :refer :all])

(defroutes app
  (GET "/" [] (slurp "./resources/index.html"))
  (GET "/api/daily-notes" [] (daily-notes))
  (GET "/api/update/:blockid" [blockid newtext] (edit-block blockid newtext))
  (GET "/api/new/block/page/:page-id" [page-id] (add-new-block-to-page page-id ""))
  (GET "/api/new/block/:block-id/" [block-id] (add-new-block-to-block block-id ""))
  (GET "/api/new/block/:block-id/:text" [block-id text] (add-new-block-to-block block-id text))
  (GET "/api/delete/block/:block-id" [block-id] (delete-block block-id))
  (GET "/api/indent/block/:block-id" [block-id] (indent-block block-id))
  (GET "/api/unindent/block/:block-id" [block-id] (unindent-block block-id))
  (GET "/app/show/:page-id" [page-id] (slurp "./resources/index.html"))
  (GET "/api/show/:page-id" [page-id] (show-page page-id))
  (GET "/api/show_or_create" [page-name] (show-or-create-page page-name))
  (GET "/api/rename_page" [page-title new-title] (rename-page page-title new-title))
  (GET "/api/references/unlinked/:page-id" [page-id] (get-unlinked-references page-id))
  (GET "/api/references/linked/:page-id" [page-id] (get-linked-references page-id))
  (GET "/api/search/pagetitles/:title-fragment" [title-fragment] (search-page-titles title-fragment))
  (GET "/api/search/all" [searchfragment] (search-page-titles-and-blocks searchfragment))
  (route/not-found "<h1>Page not found</h1>"))

(def app
  (wrap-defaults app site-defaults))