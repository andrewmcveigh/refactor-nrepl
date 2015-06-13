(ns refactor-nrepl.rename-file-or-dir
  (:require [clojure.string :as str]
            [me.raynes.fs :as fs]
            [clojure.tools.namespace.file :as file]
            [refactor-nrepl.ns
             [helpers :refer [file-content-sans-ns read-ns-form]]
             [ns-parser :as ns-parser]
             [pprint :refer [pprint-ns]]
             [tracker :as tracker]
             [rebuild :refer [rebuild-ns-form]]]
            [refactor-nrepl.util :as util])
  (:import java.io.File
           java.util.regex.Pattern))

(declare -rename-file-or-dir)

(defn- chop-src-dir-prefix
  "Given a path cuts away the part matching a dir on classpath.

  We use this as a crude way to find the source directories on the classpath."
  [path]
  (let [chop-prefix (fn [dir]
                      (->> dir
                           Pattern/quote
                           re-pattern
                           (str/split path)
                           second))
        shortest (fn [acc val] (if (< (.length acc) (.length val)) acc val))]
    (let [relative-paths (->> (util/dirs-on-classpath) (map chop-prefix) (remove nil?))]
      (if-let [p (cond
                   (= (count relative-paths) 1) (first relative-paths)
                   (> (count relative-paths) 1) (reduce shortest relative-paths))]
        (if (.startsWith p "/")
          (.substring p 1)
          p)
        (throw (IllegalStateException. (str "Can't find src dir prefix for path " path)))))))

(defn- path->ns
  "Given an absolute file path to a non-existing file determine the
  name of the ns."
  [new-path]
  (-> new-path util/normalize-to-unix-path chop-src-dir-prefix fs/path-ns))

(defn update-ns-reference-in-libspec
  [old-ns new-ns libspec]
  (if (= (:ns libspec) old-ns)
    (assoc libspec :ns new-ns)
    libspec))

(defn- update-libspecs
  "Replaces any references old-ns with new-ns in all libspecs."
  [libspecs old-ns new-ns]
  (map (partial update-ns-reference-in-libspec old-ns new-ns) libspecs))

(defn- replace-package-prefix
  [old-prefix new-prefix class]
  (if (.startsWith class old-prefix)
    (str/replace class old-prefix new-prefix)
    class))

(defn- update-class-references
  [classes old-ns new-ns]
  (let [old-prefix (str/replace (str old-ns) "-" "_")
        new-prefix (str/replace (str new-ns) "-" "_")]
    (map (partial replace-package-prefix old-prefix new-prefix) classes)))

(defn- create-new-ns-form
  "Reads file and returns an updated ns."
  [file old-ns new-ns]
  (let [ns-form (read-ns-form file)
        libspecs (ns-parser/get-libspecs ns-form)
        classes (ns-parser/get-imports ns-form)
        deps {:require (update-libspecs libspecs old-ns new-ns)
              :import (update-class-references classes old-ns new-ns)}]
    (pprint-ns (rebuild-ns-form ns-form deps))))

(defn- update-file-content-sans-ns
  "Any fully qualified references to old-ns has to be replaced with new-ns."
  [file old-ns new-ns]
  (let [old-prefix (str (str/replace old-ns "-" "_") "/")
        new-prefix (str (str/replace new-ns "-" "_") "/")
        old-ns-ref (str old-ns "/")
        new-ns-ref (str new-ns "/")]
    (-> file
        slurp
        file-content-sans-ns
        (str/replace old-prefix new-prefix)
        (str/replace old-ns-ref new-ns-ref))))

(defn- update-dependent
  "New content for a dependent file."
  [file old-ns new-ns]
  (str (create-new-ns-form file old-ns new-ns)
       "\n"
       (update-file-content-sans-ns file old-ns new-ns)))

(defn- rename-file!
  "Actually rename a file."
  [old-path new-path]
  (fs/mkdirs (fs/parent new-path))
  (fs/rename old-path new-path)
  (loop [dir (.getParentFile (File. old-path))]
    (when (empty? (.listFiles dir))
      (.delete dir)
      (recur (.getParentFile dir)))))

(defn- update-dependents!
  "Actually write new content for dependents"
  [new-dependents]
  (doseq [[f content] new-dependents]
    (spit f content)))

(defn- update-ns!
  "After moving some file to path update its ns to reflect new location."
  [path old-ns]
  (let [new-ns (path->ns path)
        f (File. path)]
    (->> new-ns
         str
         (str/replace-first (slurp f) (str old-ns))
         (spit f))))

(defn- rename-clj-file
  "Move file from old to new, updating any dependents."
  [old-path new-path]
  (let [old-ns (util/ns-from-string (slurp old-path))
        new-ns (path->ns new-path)
        dependents (tracker/get-dependents (tracker/build-tracker) old-ns)
        new-dependents (atom {})]
    (doseq [f dependents]
      (swap! new-dependents
             assoc (.getAbsolutePath f) (update-dependent f old-ns new-ns)))
    (rename-file! old-path new-path)
    (update-ns! new-path old-ns)
    (update-dependents! @new-dependents)
    (into '() (map #(.getAbsolutePath %) dependents))))

(defn- merge-paths
  "Update path with new prefix when parent dir is moved"
  [path old-parent new-parent]
  (str/replace path old-parent new-parent))

(defn- rename-dir [old-path new-path]
  (let [old-path (util/normalize-to-unix-path old-path)
        new-path (util/normalize-to-unix-path new-path)
        old-path (if (.endsWith old-path "/") old-path (str old-path "/"))
        new-path (if (.endsWith new-path "/") new-path (str new-path "/"))]
    (flatten (for [f (file-seq (File. old-path))
                   :when (not (fs/directory? f))
                   :let [path (util/normalize-to-unix-path (.getAbsolutePath f))]]
               (-rename-file-or-dir path (merge-paths path old-path new-path))))))

(defn- -rename-file-or-dir [old-path new-path]
  (let [affected-files  (if (fs/directory? old-path)
                          (rename-dir old-path new-path)
                          (if (file/clojure-file? (File. old-path))
                            (rename-clj-file old-path new-path)
                            (rename-file! old-path new-path)))]
    (->> affected-files
         flatten
         distinct
         (map util/normalize-to-unix-path)
         (remove fs/directory?)
         (filter fs/exists?)
         doall)))

(defn rename-file-or-dir
  "Renames a file or dir updating all dependent files.

  Returns a list of all files that were affected."
  [old-path new-path]
  {:pre [(not (str/blank? old-path))
         (not (str/blank? new-path))
         (or (fs/file? old-path) (fs/directory? old-path))]}
  (binding [*print-length* nil]
    (let [affected-files (-rename-file-or-dir old-path new-path)
          affected-files (->> affected-files (filter fs/exists?)
                              (remove fs/directory?))]
      (if (fs/directory? new-path)
        affected-files
        (conj affected-files new-path)))))
