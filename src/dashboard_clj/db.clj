(ns dashboard-clj.db
  (:import [java.sql SQLException])
  (:require [clojure.java.jdbc :as sql]
            [clojure.string :as s]))

;; dashboard-clj library does not concern itself with managing database
;; connections, instead it is given one when a route is called.
;; This connection must be accessible to various utilities which access
;; the database but can not simply be passed through to "defroutes".
;; Because this connection is essentially state associated with the app, it is
;; storeed as an atom and update by the main "app"

(def conn (atom nil))

(defn mysql-escape-str
  [x]
  (s/escape x {\" "\\\""}))

(defn !select
  "Select columns from table and decrypt any values whose column name is in the
  set 'decrypt'. All this, according the constraints of simple where-map."
  [db-conn table columns where-map & {:keys [decrypt append custom-where]}]
  (sql/with-connection db-conn
    (sql/with-query-results results
      (apply vector
             (str "SELECT "
                  (->> columns
                       (map #(if (contains? decrypt %)
                               (format "AES_DECRYPT(%s, UNHEX(\"%s\")) AS %s"
                                       (sql/as-identifier %)
                                       "0000" ;; config/db-encryption-key-hex
                                       (sql/as-identifier %))
                               (sql/as-identifier %)))
                       (interpose ",")
                       (apply str))
                  " FROM "
                  table
                  " WHERE "
                  (or custom-where
                      (if (empty? where-map)
                        "1"
                        (->> (keys where-map)
                             (map #(str (sql/as-identifier %) " = ?"))
                             (interpose " AND ")
                             (apply str))))
                  " "
                  append)
             (vals where-map))
      (doall results))))


;; Find out if an SQLException is for a duplicate entry for a key
(defn duplicate-entry-exception? [e]
  (seq (re-seq #"Duplicate entry.*for key" (.getMessage e))))


;; Find out if an SQLException is being caused by the table not existing
(defn table-doesnt-exist-exception? [e]
  (seq (re-seq #"Table.*doesn't exist" (.getMessage e))))

(defn insert-tpl
  "Contructs a values template (?'s) given a seq 'values' and set 'encrypt'."
  [columns encrypt]
  (s/join "," (map #(if (contains? encrypt %)
                      (format "AES_ENCRYPT(?, UNHEX(\"%s\"))"
                              "0000" ;; config/db-encryption-key-hex
                              )
                      "?")
                   columns)))

(defn insert-values
  "Inserts rows into a table with values for specified columns only.
  column-names is a vector of strings or keywords identifying columns.
  values is a vector containing values for each column in order."
  [table column-names values encrypt]
  (let [columns (map sql/as-identifier column-names)
        columns-str (s/join "," columns)
        template-str (insert-tpl column-names encrypt)]
    (apply sql/do-prepared-return-keys
           (format "INSERT INTO %s (%s) VALUES (%s)"
                   (sql/as-identifier table) columns-str template-str)
           [values])))

(defn insert-record
  "Inserts a single record into a table. A record is a map from strings or
  keywords (identifying columns) to values.
  Returns a map of the generated keys."
  [table record encrypt]
  (insert-values table (keys record) (vals record) encrypt))

(defn !insert
  "Insert row in 'table' of values 'insertion-map'.
  You can pass a set of keys to encrypt values of."
  [db-conn table insertion-map & {:keys [encrypt]}]
  (try
    (do (sql/with-connection db-conn
          (insert-record table insertion-map encrypt))
        {:success true})
    (catch SQLException e
      (cond
        (duplicate-entry-exception? e)
        {:success false
         :message "That ID is already being used."}
        (table-doesnt-exist-exception? e)
        {:success false
         :message "That table doesn't exist."}
        :else
        {:success false
         :message "SQL Error"
         :unsafeMessage e}))
    (catch Exception e
      {:success false
       :message "Unkown Error"
       :unsafeMessage e})))

(defn update-tpl
  [columns encrypt]
  (s/join ", " (map #(if (contains? encrypt %)
                       (format (str (sql/as-identifier %)
                                    "="
                                    "AES_ENCRYPT(?, UNHEX(\"%s\"))")
                               "0000" ;; config/db-encryption-key-hex
                               )
                       (str (sql/as-identifier %) "=?"))
                    columns)))

(defn update-values
  [table where-params record encrypt]
  (let [[where & params] where-params
        columns (update-tpl (keys record) encrypt)]
    (sql/do-prepared
     (format "UPDATE %s SET %s WHERE %s"
             (sql/as-identifier table) columns where)
     (concat (vals record) params))))

(defn !update
  [db-conn table update-map where-map & {:keys [encrypt]}]
  (try
    (do (sql/with-connection db-conn
          (update-values
           table
           (if (empty? where-map)
             ["1"]
             (apply vector (->> (keys where-map)
                                (map #(str (sql/as-identifier %) " = ?"))
                                (interpose " AND ")
                                (apply str))
                    (vals where-map)))
           update-map
           encrypt))
        {:success true})
    (catch SQLException e
      (cond
       (table-doesnt-exist-exception? e)
       {:success false
        :message "That table doesn't exist."}
       :else
       {:success false
        :message "SQL Error"
        :unsafeMessage e}))
    (catch Exception e
      {:success false
       :message "Unkown Error"
       :unsafeMessage e})))
