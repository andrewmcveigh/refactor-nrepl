(ns refactor-nrepl.find.find-symbol
  (:require [clojure.string :as str]
            [clojure.tools.analyzer.ast :refer [nodes postwalk]]
            [clojure.tools.namespace.find :refer [find-clojure-sources-in-dir]]
            [refactor-nrepl
             [analyzer :refer [ns-ast]]
             [util :as util]]
            [refactor-nrepl.find.find-macros :refer [find-macro]]))

(defn- node->var
  "Returns a fully qualified symbol for vars other those from clojure.core, for
  which the non-qualified name is returned."
  [alias-info node]
  (let [class (or (:class node)
                  (-> (str (:var node))
                      (str/replace "#'" "")
                      (str/replace "clojure.core/" "")))
        full-class (get alias-info class class)]
    (str/join "/" (remove nil? [full-class (:field node)]))))

(defn- fns-invoked?
  "Checks if `node` is a function-call present in `fn-set`."
  [fn-set alias-info node]
  (and (= :invoke (:op node))
       (fn-set (node->var alias-info (:fn node)))))

(defn- contains-var?
  "Checks if the var of `node` is present in the `var-set`."
  [vars-set alias-info node]
  (vars-set (node->var alias-info node)))

(defn present-before-expansion?
  "returns true if node is not result of macro expansion or if it is and it contains
  the not qualified var-name before expansion"
  [var-name node]
  (if-let [orig-form (-> node :raw-forms first str not-empty)]
    (re-find (re-pattern (str "(^|\\W)" (last (str/split var-name #"/")) "\\W")) orig-form)
    true))

(defn- dissoc-macro-nodes
  "Strips those macro nodes from the ast node which don't contain name before expansion"
  [name node]
  (if (present-before-expansion? name node)
    node
    (apply dissoc node (:children node))))

(defn- find-nodes
  "Filters `ast` with `pred` and returns a list of vectors with line-beg, line-end,
  colum-beg, column-end and the result of applying pred to the node for each
  node in the AST.

  if name present macro call sites are checked if they contained name before macro expansion"
  ([asts pred]
   (->> (mapcat nodes asts)
        (filter pred)
        (map (juxt (comp :line :env)
                   (comp :end-line :env)
                   (comp :column :env)
                   (comp :end-column :env)
                   pred))
        (map #(zipmap [:line-beg :line-end :col-beg :col-end] %))))
  ([name asts pred]
   (find-nodes (map #(postwalk % (partial dissoc-macro-nodes name)) asts) pred)))

(defn- find-invokes
  "Finds fn invokes in the AST.
  Returns a list of line, end-line, column, end-column and fn name tuples"
  [asts fn-names]
  (find-nodes asts
              #(fns-invoked? (into #{} (str/split fn-names #","))
                             (util/alias-info asts)
                             %)))

(def ^:private symbol-regex #"[\w\.:\*\+\-_!\?]+")

(defn- contains-const?
  [var-name alias-info node]
  (let [[ns name] (str/split var-name #"/")
        const-node? (= :const (:op node))
        node-val-words (when const-node?
                         (->> (str (:val node))
                              (re-seq symbol-regex)
                              set))]
    (and const-node?
         (node-val-words ns)
         (or (not name) (node-val-words name))
         var-name)))

(defn- contains-var-or-const? [var-name alias-info node]
  (or (contains-var? #{var-name} alias-info node)
      (contains-const? var-name alias-info node)))

(defn- find-symbol-in-ast [name asts]
  (when asts
    (find-nodes name
                asts
                (partial contains-var-or-const?
                         name
                         (util/alias-info asts)))))

(defn- match [file-content line end-line]
  (let [line-index (dec line)
        eline (if (number? end-line) end-line line)]
    (->> file-content
         str/split-lines
         (drop line-index)
         (take (- eline line-index))
         (str/join "\n")
         str/trim)))

(defn- find-symbol-in-file [fully-qualified-name file]
  (let [file-content (slurp file)
        locs (->> (ns-ast file-content)
                  (find-symbol-in-ast fully-qualified-name)
                  (filter :line-beg))
        gather (fn [info]
                 (merge info
                        {:file (.getCanonicalPath file)
                         :name fully-qualified-name
                         :match (match file-content
                                  (:line-beg info)
                                  (:line-end  info))}))]
    (map gather locs)))

(defn- find-global-symbol [file ns var-name clj-dir]
  (let [dir (or clj-dir ".")
        namespace (or ns (util/ns-from-string (slurp file)))
        fully-qualified-name (if (= namespace "clojure.core")
                               var-name
                               (str/join "/" [namespace var-name]))]
    (->> dir
         java.io.File.
         find-clojure-sources-in-dir
         (mapcat (partial find-symbol-in-file fully-qualified-name))
         (map identity))))

(defn- find-local-symbol
  "Find local symbol occurrences

  file is the file where the request is made
  var-name is the name of the var the user wants to know about
  line is the line number of the symbol
  column is the column of the symbol"
  [file var-name line column]
  {:pre [(number? line)
         (number? column)
         (not-empty file)]}
  (let [file-content (slurp file)
        ast (ns-ast file-content)]
    (when-let [form-index (util/top-level-form-index line column ast)]
      (let [top-level-form-ast (nth ast form-index)
            local-var-name (->> top-level-form-ast
                                nodes
                                (filter #(and (#{:local :binding} (:op %))
                                              (= var-name (-> % :form str))
                                              (:local %)))
                                (filter (partial util/node-at-loc? line column))
                                first
                                :name)]
        (map #(merge %
                     {:name var-name
                      :file (.getCanonicalPath (java.io.File. file))
                      :match (match file-content
                               (:line-beg %)
                               (:line-end %))})
             (find-nodes var-name
                         [top-level-form-ast]
                         #(and (#{:local :binding} (:op %))
                               (= local-var-name (-> % :name))
                               (:local %))))))))

(defn- to-find-symbol-result
  [{:keys [line-beg line-end col-beg col-end name file match]}]
  [line-beg line-end col-beg col-end name file match])

(defn find-symbol [{:keys [file ns name dir line column]}]
  (util/throw-unless-clj-file file)
  (map to-find-symbol-result
       (or
        ;; find-macro is first because find-global-symbol returns garb for macros
        (some->> name find-macro)
        (and (seq file) (not-empty (find-local-symbol file name line column)))
        (find-global-symbol file ns name dir))))

(defn create-result-alist
  [line-beg line-end col-beg col-end name file match]
  (list :line-beg line-beg
        :line-end line-end
        :col-beg col-beg
        :col-end col-end
        :name name
        :file file
        :match match))

(defn find-debug-fns [{:keys [ns-string debug-fns]}]
  (let [res  (-> ns-string ns-ast (find-invokes debug-fns))
        res (map to-find-symbol-result res)]
    (when (seq res)
      res)))
