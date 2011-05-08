(ns forma.source.fire
  (:use cascalog.api
        [forma.date-time :only (datetime->period)]
        [clojure.string :only (split)]
        [forma.source.modis :only (latlon->modis
                                   hv->tilestring)])
  (:require [forma.hadoop.predicate :as p]
            [cascalog.ops :as c]
            [cascalog.vars :as v])
  (:import [forma.schema FireTuple TimeSeries]
           [java.util ArrayList]))


(defn fire-tuple
  "Clojure wrapper around the java `FireTuple` constructor."
  [t-above-330 c-above-50 both-preds count]
  (FireTuple. t-above-330 c-above-50 both-preds count))

(defn fire-tseries
  "Creates a `TimeSeries` object from a start period, end period, and
  sequence of timeseries entries. This is appropriate only for
  `FireTuple` entries."
  [start end ts-seq]
  (doto (TimeSeries.)
    (.setStartPeriod start)
    (.setEndPeriod end)
    (.setValues (ArrayList. ts-seq))))

;; ### Fire Predicates

(defn format-datestring
  "Takes a datestring from our fire datasets, formatted as
  `MM/DD/YYYY`, and returns a date formatted as `YYYY-MM-DD`."
  [datestring]
  (let [[month day year] (split datestring #"/")]
    (format "%s-%s-%s" year month day)))

(p/defpredsummer [filtered-count [limit]]
  [val] #(> % limit))

(p/defpredsummer per-day
  [val] identity)

(p/defpredsummer both-preds
  [conf temp]
  (fn [c t] (and (> t 330)
                (> c 50))))

(def
  ^{:doc "Predicate macro to generate a tuple of fire characteristics
  from confidence and temperature."}
  fire-characteristics
  (<- [?conf ?kelvin :> ?tuple]
      (per-day ?conf :> ?count)
      (filtered-count [330] ?kelvin :> ?temp-330)
      (filtered-count [50] ?conf :> ?conf-50)
      (both-preds ?conf ?kelvin :> ?both-preds)
      (fire-tuple ?temp-330 ?conf-50 ?both-preds ?count :> ?tuple)))

;; ## Fire Queries

(defn fire-source
  "Takes a source of textlines, and returns 2-tuples with latitude and
  longitude."
  [source]
  (let [vs (v/gen-non-nullable-vars 5)]
    (<- [?dataset ?datestring ?t-res ?lat ?lon ?tuple]
        (source ?line)
        (p/mangle ?line :> ?lat ?lon ?kelvin _ _ ?date _ _ ?conf _ _ _)
        (p/add-fields "fire" "01" :> ?dataset ?t-res)
        (format-datestring ?date :> ?datestring)
        (fire-characteristics ?conf ?kelvin :> ?tuple))))

(defn rip-fires
  "Aggregates fire data at the supplied path by modis pixel at the
  supplied resolution."
  [m-res source]
  (let [fires (fire-source source)]
    (<- [?dataset ?m-res ?t-res ?tilestring ?datestring ?sample ?line ?tuple]
        (fires ?dataset ?datestring ?t-res ?lat ?lon ?tuple)
        (latlon->modis m-res ?lat ?lon :> ?mod-h ?mod-v ?sample ?line)
        (hv->tilestring ?mod-h ?mod-v :> ?tilestring)
        (p/add-fields m-res :> ?m-res))))

(defn extract-fields
  [f-tuple]
  [(.temp330 f-tuple)
   (.conf50 f-tuple)
   (.bothPreds f-tuple)
   (.count f-tuple)])

(defn add-fires
  "Adds together two FireTuple objects."
  [t1 t2]
  (let [[f1 f2] (map extract-fields [t1 t2])]
    (apply fire-tuple
           (map + f1 f2))))

;; Combines various fire tuples into one.

(defaggregateop merge-tuples
  ([] [0 0 0 0])
  ([state tuple] (map + state (extract-fields tuple)))
  ([state] [(apply fire-tuple state)]))

(defn aggregate-fires
  "Converts the datestring into a time period based on the supplied
  temporal resolution."
  [t-res src]
  (<- [?dataset ?m-res ?new-t-res ?tilestring ?tperiod ?sample ?line ?newtuple]
      (src ?dataset ?m-res ?t-res ?tilestring ?datestring ?sample ?line ?tuple)
      (datetime->period ?new-t-res ?datestring :> ?tperiod)
      (p/add-fields t-res :> ?new-t-res)
      (merge-tuples ?tuple :> ?newtuple)))

(defn running-sum
  "Given an accumulator, an initial value and an addition function,
  transforms the input sequence into a new sequence of equal length,
  increasing for each value."
  [acc init add-func tseries]
  (first (reduce (fn [[coll last] new]
                   (let [last (add-func last new)]
                     [(conj coll last) last]))
                 [acc init]
                 tseries)))

(defmapop [running-fire-sum [start end]]
  "Special case of `running-sum` for `FireTuple` thrift objects."
  [tseries]
  (let [empty (fire-tuple 0 0 0 0)]
    (->> tseries
         (running-sum [] empty add-fires)
         (fire-tseries start end))))

(defn fire-series
  "Aggregates fires into timeseries."
  [t-res start end src]
  (let [start (datetime->period t-res start)
        end (datetime->period t-res end)
        length (inc (- end start))
        mk-fire-tseries (p/vals->sparsevec start length (fire-tuple 0 0 0 0))]
    (<- [?dataset ?m-res ?t-res ?tilestring ?sample ?line ?ct-series]
        (src ?dataset ?m-res ?t-res ?tilestring ?tperiod ?sample ?line ?tuple)
        (mk-fire-tseries ?tperiod ?tuple :> _ ?tseries)
        (running-fire-sum [start end] ?tseries :> ?ct-series))))
