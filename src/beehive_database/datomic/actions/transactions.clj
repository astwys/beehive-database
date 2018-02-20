(ns beehive-database.datomic.actions.transactions
  (:require [datomic.api :as d]
            [beehive-database.datomic.actions.data :refer :all]
            [beehive-database.datomic.actions.queries :as queries]))

(defn transact->id [conn data]
  (let [id (d/tempid :db.part/user)
        tx @(d/transact conn [(assoc (first data) :db/id id)])
        db-after (:db-after tx)
        tempids (:tempids tx)
        real-id (d/resolve-tempid db-after tempids id)]
    real-id))


(defn add-building [address x y]
  (let [id (transact->id conn
                         [{:building/address address
                           :building/xcoord  x
                           :building/ycoord  y}])]
    id))

(defn add-hive [address x y name]
  (let [id (transact->id conn [{:building/address address
                                :building/xcoord  x
                                :building/ycoord  y
                                :building/hive    {:hive/name   name
                                                   :hive/demand -1}}])]
    @(d/transact conn (queries/connections (db) id))
    id))

(defn add-shop [address x y name]
  (let [id (transact->id conn
                         [{:building/address address
                           :building/xcoord  x
                           :building/ycoord  y
                           :building/shop    {:shop/name name}}])]
    id))


(defn add-customer [address x y name]
  (let [id (transact->id conn
                         [{:building/address  address
                           :building/xcoord   x
                           :building/ycoord   y
                           :building/customer {:customer/name name}}])]
    id))

(defn add-drone [hiveid name type status]
  (let [id (transact->id conn
                         [{:drone/name   name
                           :drone/type   (if (nil? type)
                                           (:db/id (queries/default-drone-type (d/db conn)))
                                           type)
                           :drone/status status
                           :drone/hive   hiveid}])]
    id))

(defn add-route [hops origin time]
  (let [id (transact->id conn [{:route/origin origin}])]
    @(d/transact conn (queries/mkroute (db) hops id time))
    id))

(defn tryroute [hops origin time]
  (let [id (d/tempid :db.part/user)
        tx-route (d/with (db) [{:db/id        id
                                :route/origin origin}])
        db-route (:db-after tx-route)
        tempids-route (:tempids tx-route)
        real-id (d/resolve-tempid db-route tempids-route id)
        tx (d/with db-route (queries/mkroute db-route hops real-id time))
        db (:db-after tx)]
    (queries/one :routes real-id db)))

(defn add-order
  [shopid customerid routeid source]
  (let [id (transact->id conn
                         [{:order/shop     shopid
                           :order/customer customerid
                           :order/route    routeid
                           :order/source   source}])]
    id))

(defn add-drone-type [name range speed chargetime default]
  (let [id (transact->id conn
                         [{:dronetype/name       name
                           :dronetype/range      range
                           :dronetype/speed      speed
                           :dronetype/chargetime chargetime
                           :dronetype/default    default}])]
    id))

(defn delete [id]
  @(d/transact conn
               [[:db.fn/retractEntity id]]))

(defn set-demand [hiveid demand]
  @(d/transact conn
               [{:db/id       hiveid
                 :hive/demand demand}])
  demand)

(defn departure [time hopid]
  (let [hop (queries/one :hops hopid (d/db conn))
        hiveid (:hop/start hop)
        drones (queries/drones-for-hive (:db/id hiveid) (d/db conn))
        drones-with-charge (map
                             #(assoc %
                                :charge (queries/charge-at-time (:db/id %)
                                                                time
                                                                (d/db conn)))
                             drones)
        sorted-drones-with-charge (sort-by :charge drones-with-charge)
        sorted-capable-drones (filter
                                #(> (queries/charge-at-time (:db/id %)
                                                            time
                                                            (d/db conn))
                                    (* 100
                                       (/ (:hop/distance hop)
                                          (:dronetype/range (queries/one :dronetypes (:db/id (:drone/type %)) (d/db conn))))))
                                sorted-drones-with-charge)
        selected-drone (last sorted-capable-drones)
        charge-after-hop (- (:charge selected-drone) (* 100
                                                        (/ (:hop/distance hop)
                                                           (:dronetype/range (queries/one :dronetypes (:db/id (:drone/type selected-drone)) (d/db conn))))))
        tx (d/transact conn [{:db/id         hopid
                              :hop/drone     (:db/id selected-drone)
                              :hop/endcharge charge-after-hop}])]
    [charge-after-hop selected-drone hopid]))
