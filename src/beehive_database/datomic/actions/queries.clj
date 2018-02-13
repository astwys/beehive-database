(ns beehive-database.datomic.actions.queries
  (:require [datomic.api :as d]
            [beehive-database.util :as util]
            [beehive-database.datomic.actions.rules :as rules]
            [beehive-database.datomic.actions.data :refer :all]
            [datomic-schema.schema :as datomic-schema]))


(defn all [table ids db]
  (if (empty? ids)
    (d/q '[:find [(pull ?e subquery) ...]
           :in $ subquery [?ref ...]
           :where [?e ?ref _]]
         db
         (get rules/fields table)
         (get rules/queries table))
    (d/q '[:find [(pull ?ids subquery) ...]
           :in $ subquery [?ref ...] [?ids ...]
           :where [?ids ?ref _] [?ids]]
         db
         (get rules/fields table)
         (get rules/queries table)
         ids)))

(defn drones-for-hive [hiveid db]
  (d/q '[:find [(pull ?e [:db/id
                          :drone/name
                          :drone/type
                          :drone/status]) ...]
         :in $ ?hiveid
         :where [?e :drone/hive ?hiveid]]
       db
       hiveid))

(defn hops-for-drones [droneids db]
  (d/q '[:find [(pull ?hops [*]) ...]
         :in $ [?droneids ...]
         :where [?hops :hop/drone ?droneids]]
       db
       droneids))

(defn one [table id db]
  (d/q '[:find (pull ?id subquery) .
         :in $ ?id subquery
         :where [?id]]
       db
       id
       (or (get rules/fields table) '[*])))

(defn default-drone-type [db]
  (d/q '[:find (pull ?e subquery) .
         :in $ subquery
         :where [?e :dronetype/default true]]
       db (get rules/fields :dronetypes)))

(defn max-range [db]
  (d/q '[:find (max ?e) .
         :where
         [_ :dronetype/range ?e]] db))

(defn distributions [time1 time2 db]
  (d/q '[:find [(pull ?route subquery) ...]
         :in $ ?time1 ?time2 subquery
         :where
         [?route :route/origin :route.origin/DISTRIBUTION]
         [?hop :hop/route ?route]
         [?hop :hop/starttime ?starttime]
         (or-join [?starttime ?time1 ?time2]
                  (and [(> ?starttime ?time1)]
                       [(< ?starttime ?time2)]))] db time1 time2 (get rules/fields :routes)))

(defn incoming-hops-after [hiveid time db]
  (d/q '[:find [(pull ?hop subquery) ...]
         :in $ ?hiveid ?time subquery
         :where [?hop :hop/end ?hiveid] [?hop :hop/endtime ?endtime] [(< ?time ?endtime)]]
       db
       hiveid
       time
       (get rules/fields :hops)))

(defn outgoing-hops-after [hiveid time db]
  (d/q '[:find [(pull ?hop subquery) ...]
         :in $ ?hiveid ?time subquery
         :where [?hop :hop/start ?hiveid] [?hop :hop/starttime ?starttime] [(< ?time ?starttime)]]
       db
       hiveid
       time
       (get rules/fields :hops)))

(defn order-with-route [routeid db]
  (d/q '[:find (pull ?order subquery) .
         :in $ ?routeid subquery
         :where [?order :order/route ?routeid]]
       db
       routeid
       (get rules/fields :orders)))

(defn conns [hiveids db]
  (if (nil? hiveids)
    (all :connections hiveids db)
    (d/q '[:find [(pull ?conn subquery) ...]
           :in $ [?hiveids ...] subquery
           :where (or-join [?conn ?hiveids]
                           [?conn :connection/start ?hiveids]
                           [?conn :connection/end ?hiveids])]
         db
         hiveids
         (get rules/fields :connections))))


(defn is-reachable [p1 p2 db]
  (util/reachable p1 p2 (max-range db)))

(defn reachable [buildingid db]
  (let [buildings (remove
                    #(= (:db/id %) buildingid)
                    (all :hives [] db))
        building (one :hives buildingid db)]
    (filter
      #(is-reachable
         (util/position building)
         (util/position %)
         db)
      buildings)))

(datomic-schema/defdbfn connections [db hiveid] :db.part/user
                        (mapv
                          (fn [x]
                            {:db/id               (datomic.api/tempid :db.part/user)
                             :connection/start    hiveid
                             :connection/end      (:db/id x)
                             :connection/distance (beehive-database.util/distance
                                                    (beehive-database.util/position
                                                      (beehive-database.datomic.actions.queries/one :buildings hiveid db))
                                                    (beehive-database.util/position x))})
                          (beehive-database.datomic.actions.queries/reachable hiveid db)))

(datomic-schema/defdbfn mkroute [db hops routeid time] :db.part/user
                        (let [speed (:dronetype/speed (beehive-database.datomic.actions.queries/default-drone-type db))]
                          (loop [result []
                                 lhops hops
                                 starttime time]
                            (let [endtime (long (+
                                                  starttime
                                                  (* 1000 (beehive-database.util/travel-time
                                                            (beehive-database.util/position (beehive-database.datomic.actions.queries/one :hives (:from (first hops)) db))
                                                            (beehive-database.util/position (beehive-database.datomic.actions.queries/one :hives (:to (first hops)) db))
                                                            speed))))]
                              (if (empty? lhops)
                                result
                                (recur (conj result {:hop/route     routeid
                                                     :hop/start     (:from (first lhops))
                                                     :hop/end       (:to (first lhops))
                                                     :hop/starttime starttime
                                                     :hop/endtime   endtime})
                                       (drop 1 lhops)
                                       (+ endtime 3000)))))))

(defn connections-with-shop-cust [hiveids shopid custid db]
  (let [conns (conns hiveids db)]
    (-> conns
        (concat (if (not (nil? shopid))
                  (connections db shopid)
                  nil))
        (concat (if (not (nil? custid))
                  (connections db custid)
                  nil)))))


(defn connections-with-shop-cust [hiveids shopid custid db]
  (if (not (nil? shopid)) @(d/transact conn [[:connections shopid]]))
  (if (not (nil? custid)) @(d/transact conn [[:connections custid]]))
  (conns (concat hiveids [shopid custid]) (d/db conn)))

