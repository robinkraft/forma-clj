(ns forma.source.fire
  "This namespace has functions and Cascalog queries for processing fire data.

  Fires are recorded daily by the Fire Information For Resource Management
  System (http://maps.geog.umd.edu) using the NASA Terra and Aqua satellites.
  They are described using the following attributes which are stored in a CSV
  file:

    http://firefly.geog.umd.edu/firms/faq.htm#attributes

  Here's an example CSV file:

     latitude,longitude,brightness,scan,track,acq_date,acq_time,satellite,confidence,version,bright_t31,frp
    -21.774,-48.371,328.8,2.6,1.6,05/11/2012,0125,T,100,5.0,280.7,164.3
    -21.770,-48.378,327.4,2.6,1.6,05/11/2012,0125,T,100,5.0,280.3,157.4
    -16.615,-43.483,307.5,1.1,1.0,05/11/2012,0125,T,55,5.0,285.7,15.8

  The FORMA algorithm depends on latitude, longitude, brightness, confidence
  and acq_date. Before these fires can be used, they have to fist get converted
  from latitude and longitude into MODIS pixel coordinates at 500 meter resolution.

  Once converted, we store each fire in a DataChunk Thrift object and store it
  into a Pail. Pails are cool because you can append to them. This allows us to
  incrementally process fire data. To see the Thrift object definitions, check
  out the dev/forma.thirft IDL.

  After getting converted, here's what a fire DataChunk looks like for a
  single fire.

    DataChunk
      dataset - The name of the dataset which is just 'fire'
      locationProperty - The ModisPixelLocation Thrift object.
      chunkValue - The FireTuple Thrift object.
      temporalRes - The temporal resolution of fires which is one day.
      date - The acq_date field for the fire.

  The FireTuple Thrift object defines properties of a single fire or multiple
  fires. In this step, it represents a single fire. The 'temp330' is set to 1
  if the fire is greater than 330 degrees Kelvin. The 'conf50' is set to 1 if
  the fire confidence is greater than 50. If 'temp330' and 'conf50' are both 1,
  the 'bothPreds' is set to 1. For a single fire, the 'count' is set to 1. 

  At the end of this step, we have a single Pail full of DataChunk objects, one
  per fire, each which represents the fire in MODIS pixel coordinates.

  Note: We received fire data organized into files by month through February
  2010 from the University of Maryland. These are stored on S3 in the
  s3://modisfiles/MonthlyFires directory. More recent fire data are organized
  into daily files. The difference between them is the date formatting and
  we have functions here to convert between them.

    Daily:  MM/DD/YYYY
    Monthly: YYYYMMDD"
  (:use cascalog.api
        [forma.date-time :only (convert)])
  (:require [clojure.string :as s]
            [forma.utils :as utils]
            [forma.schema :as schema]
            [forma.reproject :as r]
            [forma.hadoop.io :as io]
            [forma.hadoop.predicate :as p]))

(defn daily-datestring
  "Returns a daily date string formatted as YYYY-MM-DD.

   Arguments:
     date-str - Daily date string formatted as MM/DD/YYYY.

  Example usage:
    > (daily->standard 1977/02/02)
    02-1977-02"
  [date-str]
  (let [[month day year] (s/split date-str #"/")]
    (s/join "-" [year month day])))

(defn monthly-datestring
  "Returns a monthly date string formatted as YYYY-MM-DD.

   Arguments:
     date-str - Monthly date string formatted as YYYYMMDD.

  Example usage:
    > (monthly->standard 19770202)
    1977-02-02"  
  [date-str]
  (convert date-str :basic-date :year-month-day))

(def fire-characteristics
  "Returns a Cascalog predicate macro that creates a FireTuple Thrift object.

  Input variables:
    ?conf - The fire confidence value.
    ?kelvin - The fire degress in Kelvin

  Output variables:
    ?tuple - The FireTuple Thrift object.

  Example usage:

    > (let [src [[60 380]]]
            (??<-
            [?tuple]
            (src ?conf ?kelvin)
            (fire-characteristics ?conf ?kelvin :> ?tuple)))"
  (<- [?conf ?kelvin :> ?tuple]
      (p/full-count ?conf :> ?count)
      (p/filtered-count [330] ?kelvin :> ?temp-330)
      (p/filtered-count [50] ?conf :> ?conf-50)
      (p/bi-filtered-count [50 330] ?conf ?kelvin :> ?both-preds)
      (schema/fire-value ?temp-330 ?conf-50 ?both-preds ?count :> ?tuple)))

(def fire-pred
 "Returns a Cascalog predicate macro that creates a FireTuple Thrift object.

  Input variables:
    ?s-lat - The string latitude for the fire.
    ?s-lon - The string latitude for the fire.
    ?s-kelvin - The string kelvin value for the fire.
    ?s-conf - The string confidence value for the fire.

  Output variables:
    ?dataset - The fire dataset name (fire).
    ?t-res - The fire temporal resolution (1 day).
    ?lat - The fire latitude as a float.
    ?lon - The fire longitude as a float.
    ?tuple - The FireTuple Thrift object representing this fire.

  Example usage:
    > (let [src [[\"1\" \"2\" \"331\" \"51\"]]]
           (??<-
            [?dataset ?t-res ?lat ?lon ?fire-tuple]
            (src ?s-lat ?s-lon ?s-kelvin ?s-conf)
            (fire-pred ?s-lat ?s-lon ?s-kelvin ?s-conf :> ?dataset ?t-res ?lat ?lon ?tuple)))"
 (<- [?s-lat ?s-lon ?s-kelvin ?s-conf :> ?dataset ?t-res ?lat ?lon ?tuple]
      (utils/strings->floats ?s-lat ?s-lon ?s-kelvin ?s-conf
                             :> ?lat ?lon ?kelvin ?conf)
      (p/add-fields "fire" "01" :> ?dataset ?t-res)
      (fire-characteristics ?conf ?kelvin :> ?tuple)))

(defn fire-source-monthly
  "Returns a Cascalog query that creates tuples for monthly fires.

  Source:
    src - An hfs-textline of monthly fires (datestring _ _ s-lat s-lon s-kelvin _ _ _ s-conf)

  Output variables:
    ?dataset - The dataset name (fires).
    ?date - The fire date formatted as YYYY-MM-DD.
    ?t-res - The fire temporal resolution (1).
    ?lat - The fire latitude as a float.
    ?lon - The fire longitude as a float."
  [src]
  (<- [?dataset ?date ?t-res ?lat ?lon ?tuple]
      (src ?line)
      (p/mangle [#"\s+"] ?line
                :> ?datestring _ _ ?s-lat ?s-lon ?s-kelvin _ _ _ ?s-conf)
      (not= "YYYYMMDD" ?datestring)
      (monthly-datestring ?datestring :> ?date)
      (fire-pred ?s-lat ?s-lon ?s-kelvin ?s-conf :> ?dataset ?t-res ?lat ?lon ?tuple)))

(defn fire-source-daily
  "Returns a Cascalog query that creates tuples for daily fires.

  Source:
    src - An hfs-textline of daily fires (lat lon kelvin _ _ date _ _ conf _ _ _)

  Output variables:
    ?dataset - The dataset name (fires).
    ?date - The fire date formatted as YYYY-MM-DD.
    ?t-res - The fire temporal resolution (1).
    ?lat - The fire latitude as a float.
    ?lon - The fire longitude as a float.
    ?tuple - The FireTuple Thrift object representing the fire."
  [src]
  (<- [?dataset ?date ?t-res ?lat ?lon ?tuple]
      (src ?line)
      (p/mangle [#","] ?line
                :> ?s-lat ?s-lon ?s-kelvin _ _ ?datestring _ _ ?s-conf _ _ _)
      (daily-datestring ?datestring :> ?date)
      (fire-pred ?s-lat ?s-lon ?s-kelvin ?s-conf :> ?dataset ?t-res ?lat ?lon ?tuple)))

(defn reproject-fires
  "Returns a Cascalog query that creates DataChunk Thrift objects for fires."
  [m-res src]
  (<- [?datachunk]
      (p/add-fields m-res :> ?m-res)
      (src ?dataset ?date ?t-res ?lat ?lon ?tuple)
      (schema/mk-data-value ?tuple :> ?tuple-val)
      (r/latlon->modis ?m-res ?lat ?lon :> ?mod-h ?mod-v ?sample ?line)
      (schema/pixel-location ?m-res ?mod-h ?mod-v ?sample ?line :> ?location)
      (schema/chunk-value ?dataset ?t-res ?date ?location ?tuple-val :> ?datachunk)))
