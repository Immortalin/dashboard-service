(ns dashboard-service.handler
  (:require [clojure.string :as s]
            [clojure.walk :refer [keywordize-keys stringify-keys]]
            [compojure.core :refer :all]
            [compojure.handler :refer [site]]
            [compojure.route :as route]
            [clout.core :as clout]
            [ring.util.response :refer [header set-cookie response redirect]]
            [buddy.auth.accessrules :refer [wrap-access-rules]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [dashboard-service.analytics :as analytics]
            [dashboard-service.couriers :refer [get-by-id include-lateness
                                            update-courier-zones!]]
            [common.config :as config]
            [common.couriers :as couriers]
            [dashboard-service.coupons :refer [create-standard-coupon!
                                           coupons
                                           update-standard-coupon!]]
            [common.coupons :as coupons]
            [common.db :refer [!select conn]]
            [dashboard-service.login :as login]
            [common.orders :as orders]
            [dashboard-service.orders :refer [include-eta
                                          include-user-name-phone-and-courier
                                          include-vehicle
                                          include-was-late
                                          include-zone-info
                                          orders-since-date
                                          ]]
            [dashboard-service.pages :as pages]
            [dashboard-service.users :refer [dash-users
                                         send-push-to-all-active-users
                                         send-push-to-users-list]]
            [common.users :as users]
            [common.util :refer [convert-timestamp convert-timestamps]]
            [common.zones :as zones]
            [dashboard-service.zones :refer [get-zone-by-id
                                         read-zone-strings
                                         validate-and-update-zone!]]))

(defn wrap-page [resp]
  (header resp "content-type" "text/html; charset=utf-8"))


(defn valid-session-wrapper?
  "given a request, determine if the user-id has a valid session"
  [request]
  (let [cookies (keywordize-keys
                 ;; the reason resolve is used here is because
                 ;; parse-cookies is not a public fn, this hack
                 ;; gets around that
                 ((resolve 'ring.middleware.cookies/parse-cookies) request))
        user-id (get-in cookies [:user-id :value])
        token   (get-in cookies [:token :value])]
    (users/valid-session? (conn) user-id token)))

(def login-rules
  [{:pattern #".*/dashboard/login" ; this route must always be allowed access
    :handler (constantly true)}
   {:pattern #".*/dashboard/logout" ; this route must always be allowed access
    :handler (constantly true)}
   {:pattern #".*/dashboard(/.*|$)"
    :handler valid-session-wrapper?
    :redirect "/dashboard/login"}])

;; if the route is omitted, it is accessible by all dashboard users
;; url and method must be given, if one is missing, the route will be allowed!
;; login/logout should be unprotected!
;;
;; these routes are organized to match those under /dashboard
;; and follow the same conventions.
;;
;; a user who can access everything would have the following permissions:
;; #{"view-dash","view-couriers","edit-couriers","view-users","send-push",
;;  "view-coupons","edit-coupons","create-coupons","view-zones","edit-zones",
;;  "view-orders","edit-orders","download-stats"}
;;
(def dashboard-uri-permissions
  [
   ;;!! main dash
   {:uri "/dashboard/"
    :method "GET"
    :permissions ["view-dash"]}
   {:uri "/dashboard"
    :method "GET"
    :permissions ["view-dash"]}
   {:uri "/dashboard/dash-app"
    :method "GET"
    :permissions ["view-dash"]}
   {:uri "/dashboard/permissions"
    :method "GET"
    :permissions ["view-dash"]}
   ;;!! dash maps
   {:uri "/dashboard/dash-map-orders"
    :method "GET"
    :permissions ["view-orders" "view-zones"]}
   {:uri "/dashboard/dash-map-couriers"
    :method "GET"
    :permissions ["view-orders" "view-couriers" "view-zones"]}
   ;;!! couriers
   {:uri "/dashboard/courier/:id"
    :method "GET"
    :permissions ["view-couriers"]}
   {:uri "/dashboard/courier"
    :method "POST"
    :permissions ["edit-couriers"]}
   {:uri "/dashboard/couriers"
    :method "POST"
    :permissions ["view-couriers"]}
   ;;!! users
   {:uri "/dashboard/users"
    :method "GET"
    :permissions ["view-users" "view-orders"]}
   {:uri "/dashboard/users-count"
    :method "GET"
    :permissions ["view-users" "view-orders"]}
   {:uri "/dashboard/send-push-to-all-active-users"
    :method "POST"
    :permissions ["view-users" "send-push"]}
   {:uri "/dashboard/send-push-to-users-list"
    :method "POST"
    :permissions ["view-users" "send-push"]}
   ;;!! coupons
   {:uri "/dashboard/coupon/:code"
    :method "GET"
    :permissions ["view-coupons"]}
   {:uri "/dashboard/coupon"
    :method "PUT"
    :permissions ["edit-coupons"]}
   {:uri "/dashboard/coupon"
    :method "POST"
    :permissions ["create-coupons"]}
   {:uri "/dashboard/coupons"
    :method "GET"
    :permissions ["view-coupons"]}
   ;;!! zones
   {:uri "/dashboard/zone/:id"
    :method "GET"
    :permissions ["view-zones"]}
   {:uri "/dashboard/zone"
    :method "PUT"
    :permissions ["edit-zones"]}
   {:uri "/dashboard/zones"
    :method "GET"
    :permissions ["view-zones"]}
   {:uri "/dashboard/zctas"
    :method "POST"
    :permissions ["view-zones"]}
   ;;!! orders
   {:uri "/dashboard/order"
    :method "POST"
    :permissions ["view-orders"]}
   {:uri "/dashboard/cancel-order"
    :method "POST"
    :permissions ["edit-orders"]}
   {:uri "/dashboard/update-status"
    :method "POST"
    :permissions ["edit-orders"]}
   {:uri "/dashboard/assign-order"
    :method "POST"
    :permissions ["edit-orders"]}
   {:uri "/dashboard/orders-since-date"
    :method "POST"
    :permissions ["view-orders"]}
   ;;!! analytics
   {:uri "/dashboard/status-stats-csv"
    :method "GET"
    :permissions ["download-stats"]}
   {:uri "/dashboard/generate-stats-csv"
    :method "GET"
    :permissions ["download-stats"]}
   {:uri "/dashboard/download-stats-csv"
    :method "GET"
    :permissions ["download-stats"]}
   ])

(defn allowed?
  "given a vector of uri-permission maps and a request map, determine if the
  user has permission to access the response of a request"
  [uri-permissions request]
  (let [cookies   (keywordize-keys
                   ;; parse-cookies is not a public fn, so it is resolved
                   ((resolve 'ring.middleware.cookies/parse-cookies) request))
        user-id   (get-in cookies [:user-id :value])
        user-permission  (login/get-permissions (conn) user-id)
        uri       (:uri request)
        method    (-> request
                      :request-method
                      name
                      s/upper-case)
        uri-permission  (:permissions (first (filter #(and
                                                       (clout/route-matches
                                                        (:uri %) {:uri uri})
                                                       (= method (:method %)))
                                                     uri-permissions)))
        user-uri-permission-compare (map #(contains? user-permission %)
                                         uri-permission )
        user-has-permission? (boolean (and (every? identity
                                                   user-uri-permission-compare)
                                           (seq user-uri-permission-compare)))]
    (cond (empty? uri-permission) ; no permission associated with uri, allow
          true
          :else user-has-permission?)))

(defn on-error
  [request value]
  {:status 403
   :header {}
   :body (str "you do not have permission to access " (:uri request))})

(def access-rules
  [{:pattern #".*/dashboard(/.*|$)"
    :handler (partial allowed? dashboard-uri-permissions)
    :on-error on-error}])

;; the following convention is used for ordering dashboard
;; routes
;; 1. routes are organized under groups (e.g. ;;!! group)
;;    and mirror the order of the dashboard navigation tabs.
;; 2. requests that result in a single object come before
;;    those that potentially result in multiple objects.
;; 3. if there are multiple methods for the same route,
;;    list them in the order get, put, post
;; 4. routes that don't fit a particular pattern are listed
;;    last
;; 5. try to be logical and use your best judgement when these
;;    rules fail
(defroutes dashboard-routes
  ;;!! main dash
  (GET "/" []
       (-> (pages/dash-app)
           response
           wrap-page))
  (GET "/permissions" {cookies :cookies}
       (let [user-perms (login/get-permissions
                         (conn)
                         (get-in cookies ["user-id" :value]))
             accessible-routes (login/accessible-routes
                                dashboard-uri-permissions
                                user-perms)]
         (response (into [] accessible-routes))))
  ;;!! login / logout
  (GET "/login" []
       (-> (pages/dash-login)
           response
           wrap-page))
  (POST "/login" {body :body
                  headers :headers
                  remote-addr :remote-addr}
        (response
         (let [b (keywordize-keys body)]
           (login/login (conn) (:email b) (:password b)
                        (or (get headers "x-forwarded-for")
                            remote-addr)))))
  (GET "/logout" []
       (-> (redirect "/dashboard/login")
           (set-cookie "token" "null" {:max-age -1})
           (set-cookie "user-id" "null" {:max-age -1})))
  ;;!! dash maps
  (GET "/dash-map-orders" []
       (-> (pages/dash-map :callback-s
                           "dashboard_cljs.core.init_map_orders")
           response
           wrap-page))
  (GET "/dash-map-couriers" []
       (-> (pages/dash-map :callback-s
                           "dashboard_cljs.core.init_map_couriers")
           response
           wrap-page))
  ;;!! couriers
  ;; return all couriers
  ;; get a courier by id
  (GET "/courier/:id" [id]
       (response
        (into []
              (->> (get-by-id (conn) id)
                   list
                   (users/include-user-data (conn))
                   (include-lateness (conn))))))
  ;; update a courier
  ;; currently, only the zones can be updated
  (POST "/courier" {body :body}
        (let [b (keywordize-keys body)]
          (response (update-courier-zones!
                     (conn)
                     (:id b)
                     (:zones b)))))
  (POST "/couriers" {body :body}
        (response
         (let [b (keywordize-keys body)]
           {:couriers (->> (couriers/all-couriers (conn))
                           (users/include-user-data (conn))
                           (include-lateness (conn)))})))
  ;;!! users
  (GET "/users" []
       (response
        (into []
              (dash-users (conn)))))
  (GET "/users-count" []
       (response
        (into []
              (!select (conn) "users" ["count(*) as total"] {}))))
  (POST "/send-push-to-all-active-users" {body :body}
        (response
         (let [b (keywordize-keys body)]
           (send-push-to-all-active-users (conn)
                                          (:message b)))))
  (POST "/send-push-to-users-list" {body :body}
        (response
         (let [b (keywordize-keys body)]
           (send-push-to-users-list (conn)
                                    (:message b)
                                    (:user-ids b)))))
  ;;!! coupons
  ;; get a coupon by code
  (GET "/coupon/:code" [code]
       (response
        (into []
              (->> (coupons/get-coupon-by-code (conn) code)
                   convert-timestamp
                   list))))
  ;; edit an existing coupon
  (PUT "/coupon" {body :body}
       (let [b (keywordize-keys body)]
         (response
          (update-standard-coupon! (conn) b))))
  ;; create a new coupon
  (POST "/coupon" {body :body}
        (let [b (keywordize-keys body)]
          (response
           (create-standard-coupon! (conn) b))))
  ;; get the current coupon codes
  (GET "/coupons" []
       (response
        (into []
              (-> (coupons (conn))
                  (convert-timestamps)))))
  ;;!! zones
  ;; get a zone by its id
  (GET "/zone/:id" [id]
       (response
        (into []
              (->> (get-zone-by-id (conn) id)
                   (read-zone-strings)
                   list))))
  ;; update a zone's description. currently only supports
  ;; updating fuel_prices, service_fees and service_time_bracket
  (PUT "/zone" {body :body}
       (let [b (keywordize-keys body)]
         (response
          (validate-and-update-zone! (conn) b))))
  ;; return all zones
  (GET "/zones" []
       (response
        ;; needed because cljs.reader/read-string can't handle
        ;; keywords that begin with numbers
        (mapv
         #(assoc %
                 :fuel_prices (stringify-keys
                               (read-string (:fuel_prices %)))
                 :service_fees (stringify-keys
                                (read-string (:service_fees %)))
                 :service_time_bracket (read-string
                                        (:service_time_bracket %)))
         (into [] (zones/get-all-zones-from-db (conn))))))
  ;; return zcta defintions for zips
  (POST "/zctas" {body :body}
        (response
         (let [b (keywordize-keys body)]
           {:zctas
            (zones/get-zctas-for-zips (conn) (:zips b))})))
  ;;!! orders
  ;; given an order id, get the detailed information for that
  ;; order
  (POST "/order"  {body :body}
        (response
         (let [b (keywordize-keys body)]
           (if (:id b)
             (into []
                   (->>
                    [(orders/get-by-id (conn)
                                       (:id b))]
                    (include-user-name-phone-and-courier
                     (conn))
                    (include-vehicle (conn))
                    (include-zone-info)
                    (include-eta (conn))
                    (include-was-late)))
             []))))
  ;; cancel the order
  (POST "/cancel-order" {body :body}
        (response
         (let [b (keywordize-keys body)]
           (orders/cancel
            (conn)
            (:user_id b)
            (:order_id b)
            :origin-was-dashboard true
            :notify-customer true
            :suppress-user-details true
            :override-cancellable-statuses
            (conj config/cancellable-statuses "servicing")))))
  ;; admin updates status of order (e.g., enroute -> servicing)
  (POST "/update-status" {body :body}
        (response
         (let [b (keywordize-keys body)]
           (orders/update-status-by-admin (conn)
                                          (:order_id b)))))
  ;; admin assigns courier to an order
  (POST "/assign-order" {body :body}
        (response
         (let [b (keywordize-keys body)]
           (orders/assign-to-courier-by-admin (conn)
                                              (:order_id b)
                                              (:courier_id b)))))
  ;; given a date in the format yyyy-mm-dd, return all orders
  ;; that have occurred since then
  (POST "/orders-since-date"  {body :body}
        (response
         (let [b (keywordize-keys body)]
           (into [] (->>
                     (orders-since-date (conn)
                                        (:date b)
                                        (:unix-epoch? b))
                     (include-user-name-phone-and-courier
                      (conn))
                     (include-vehicle (conn))
                     (include-zone-info)
                     (include-was-late))))))
  ;;!! analytics
  (GET "/status-stats-csv" []
       (response
        (let [stats-file (java.io.File. "stats.csv")]
          (if (> (.length stats-file) 0)
            {:processing? false
             :timestamp (quot (.lastmodified stats-file)
                              1000)}
            {:processing? true}))))
  ;; generate analytics file
  (GET "/generate-stats-csv" []
       (do (future (analytics/gen-stats-csv))
           (response {:success true})))
  (GET "/download-stats-csv" []
       (-> (response (java.io.File. "stats.csv"))
           (header "content-type:"
                   "text/csv; name=\"stats.csv\"")
           (header "content-disposition"
                   "attachment; filename=\"stats.csv\"")))
  (route/resources "/"))

(defn dashboard []
  (->
   dashboard-routes
   (wrap-access-rules {:rules access-rules})
   (wrap-access-rules {:rules login-rules})
   (wrap-cookies)))

;; used for running dashboard-service independently of app-service
(defroutes handler-routes
  (context "/dashboard" []
           dashboard-routes)
  (route/resources "/"))

(def handler
  (->
   handler-routes
   (wrap-access-rules {:rules access-rules})
   (wrap-access-rules {:rules login-rules})
   (wrap-cookies)
   (wrap-json-body)
   (wrap-json-response)))