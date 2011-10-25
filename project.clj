(defproject forma "0.2.0-SNAPSHOT"
  :description "[FORMA](http://goo.gl/4YTBw) gone Functional."
  :source-path "src/clj"
  :java-source-path "src/jvm"
  :dev-resources-path "dev"
  :marginalia {:javascript ["mathjax/MathJax.js"]}
  :javac-options {:debug "true" :fork "true"}
  :run-aliases {:cluster forma.hadoop.cluster}
  :jvm-opts ["-XX:MaxPermSize=128M" "-Xms1024M" "-Xmx2048M" "-server"
             "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n"]
  :repositories {"releases" "http://oss.sonatype.org/content/repositories/releases/"
                 "snapshots" "http://oss.sonatype.org/content/repositories/snapshots/"
                 "conjars" "http://conjars.org/repo/"}
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [incanter "1.2.3" :exclusions [swank-clojure]]
                 [clj-time "0.3.1"]
                 [redd/thrift "0.5.0"]
                 [commons-lang "2.6"]   ;required for thrift
                 [cascalog "1.8.2"]
                 [backtype/cascading-thrift "0.1.0" :exclusions [backtype/thriftjava]]
                 [backtype/dfs-datastores "1.0.5"]
                 [backtype/dfs-datastores-cascading "1.0.4"]]
  :native-dependencies [[org.clojars.sritchie09/gdal-java-native "1.8.0"]]
  :native-path "lib/ext/native:lib/native:lib/dev/native"
  :dev-dependencies [[org.apache.hadoop/hadoop-core "0.20.2-dev"]
                     [redd/native-deps "1.0.7"]
                     [pallet-hadoop "0.3.2"]
                     [org.jclouds.provider/aws-ec2 "1.0.0"]
                     [org.jclouds.driver/jclouds-jsch "1.0.0"]
                     [org.jclouds.driver/jclouds-log4j "1.0.0"]
                     [log4j/log4j "1.2.14"]
                     [vmfest/vmfest "0.2.2"]
                     [swank-clojure "1.4.0-SNAPSHOT"]
                     [clojure-source "1.2.0"]
                     [lein-marginalia "0.6.1"]
                     [lein-midje "1.0.4"]
                     [midje-cascalog "0.3.0"]]
  :aot [
        forma.hadoop.pail
        forma.hadoop.jobs.scatter
        forma.hadoop.jobs.preprocess
        forma.hadoop.jobs.modis
        forma.hadoop.jobs.timeseries
        ])

;; Robert Hooke!

(use '[robert.hooke :only [add-hook]]
     '[leiningen.deps :only [deps]]
     '[leiningen.clean :only [clean]]
     '[leiningen.uberjar :only [uberjar]])
(require '[leiningen.compile :as c])

(defn append-tasks
  [target-var & tasks-to-add]
  (add-hook target-var (fn [target project & args]
                         (apply target project args)
                         (doseq [t tasks-to-add]
                           (t project)))))

(prepend-tasks #'deps clean)
(prepend-tasks #'uberjar c/compile)

(try (use '[leiningen.native-deps :only (native-deps)])
     (when-let [native (resolve 'native-deps)]
       (append-tasks #'deps @native c/compile))
     (catch java.lang.Exception _
       (println "Run lein deps again to activate the
                 required native-deps and compile hooks.")))
