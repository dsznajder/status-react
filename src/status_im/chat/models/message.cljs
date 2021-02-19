(ns status-im.chat.models.message
  (:require [re-frame.core :as re-frame]
            [status-im.chat.models :as chat-model]
            [status-im.chat.models.message-list :as message-list]
            [status-im.constants :as constants]
            [status-im.data-store.messages :as data-store.messages]
            [status-im.ethereum.json-rpc :as json-rpc]
            [status-im.multiaccounts.model :as multiaccounts.model]
            [status-im.transport.message.protocol :as protocol]
            [status-im.utils.fx :as fx]
            [taoensso.timbre :as log]
            [status-im.chat.models.mentions :as mentions]
            [clojure.string :as string]
            [status-im.contact.db :as contact.db]
            [status-im.utils.types :as types]))

(defn- prepare-message
  [message current-chat?]
  (cond-> message
    current-chat?
    (assoc :seen true)))

(fx/defn rebuild-message-list
  [{:keys [db]} chat-id]
  {:db (assoc-in db [:message-lists chat-id]
                 (message-list/add-many nil (vals (get-in db [:messages chat-id]))))})

(fx/defn hidden-message-marked-as-seen
  {:events [::hidden-message-marked-as-seen]}
  [{:keys [db] :as cofx} chat-id _ hidden-message-count]
  (when (= 1 hidden-message-count)
    {:db (update-in db [:chats chat-id]
                    update
                    :unviewed-messages-count dec)}))

(fx/defn hide-message
  "Hide chat message, rebuild message-list"
  [{:keys [db] :as cofx} chat-id {:keys [seen message-id]}]
  (fx/merge cofx
            {:db (update-in db [:messages chat-id] dissoc message-id)}
            (data-store.messages/mark-messages-seen chat-id [message-id] #(re-frame/dispatch [::hidden-message-marked-as-seen %1 %2 %3]))
            (rebuild-message-list chat-id)))

(fx/defn add-message [{:keys [db]}
                      {:keys [chat-id message-id] :as message}]
  (let [prepared-message (prepare-message message (= (:view-id db) :chat))]
    {:db (-> db
             ;; We should not be always adding to the list, as it does not make sense
             ;; if the chat has not been initialized, but run into
             ;; some troubles disabling it, so next time
             (update-in [:messages chat-id] assoc message-id prepared-message)
             (update-in [:message-lists chat-id] message-list/add prepared-message))}))

(fx/defn add-and-hide-message
  [{:keys [db] :as cofx}
   {:keys [chat-id message-id replace timestamp from] :as message}]
  (let [message-to-be-removed (when replace
                                (get-in db [:messages chat-id replace]))]
    (fx/merge cofx
              (when message-to-be-removed
                (hide-message chat-id message-to-be-removed))
              (add-message message))))

(fx/defn add-sender-to-chat-users
  [{:keys [db]} {:keys [chat-id alias name identicon from]}]
  (when (and alias (not= alias ""))
    {:db (update-in db [:chats chat-id :users] assoc
                    from
                    (mentions/add-searchable-phrases
                     {:alias      alias
                      :name       (or name alias)
                      :identicon  identicon
                      :public-key from
                      :nickname   (get-in db [:contacts/contacts from :nickname])}))}))

(fx/defn add-received-message
  [{:keys [db] :as cofx} message]
  (fx/merge
   cofx
   (add-and-hide-message message)
   (add-sender-to-chat-users message)))

(defn- message-loaded?
  [db chat-id message-id]
  (get-in db [:messages chat-id message-id]))

(defn- earlier-than-deleted-at?
  [db chat-id clock-value]
  (>= (get-in db [:chats chat-id :deleted-at-clock-value]) clock-value))

(fx/defn update-unviewed-count
  [{:keys [db] :as cofx} {:keys [chat-id from message-type message-id new?]}]
  (when-not (= message-type constants/message-type-private-group-system-message)
    (let [{:keys [current-chat-id view-id]} db
          chat-view?         (= :chat view-id)]
      (cond
        (= from (multiaccounts.model/current-public-key cofx))
       ;; nothing to do
        nil

        (and chat-view? (= current-chat-id chat-id))
        (fx/merge cofx
                  (data-store.messages/mark-messages-seen current-chat-id [message-id] nil))

        new?
        {:db (update-in db [:chats chat-id :unviewed-messages-count] inc)}))))

(fx/defn check-for-incoming-tx
  [cofx {{:keys [transaction-hash]} :command-parameters}]
  (when (and transaction-hash
             (not (string/blank? transaction-hash)))
    ;; NOTE(rasom): dispatch later is needed because of circular dependency
    {:dispatch-later
     [{:dispatch [:watch-tx transaction-hash]
       :ms       20}]}))

(fx/defn receive-one
  {:events [::receive-one]}
  [{:keys [db] :as cofx} {:keys [message-id chat-id clock-value] :as message}]
  ;;we add message only if chat was intialized and already has loaded messages
  (when (get-in db [:pagination-info chat-id :messages-initialized?])
    (fx/merge cofx
              ;;If its a profile updates we want to add this message to the timeline as well
              #(when-let [contact-pub-key (get-in cofx [:db :chats chat-id :profile-public-key])]
                 (when (contact.db/added? db contact-pub-key)
                   {:dispatch-n [[::receive-one (assoc message :chat-id constants/timeline-chat-id)]]}))
              ;;TODO what is earlier-than-deleted-at? for ?
              #(when-not (earlier-than-deleted-at? db chat-id clock-value)
                 (if (message-loaded? db chat-id message-id)
                   ;; If the message is already loaded, it means it's an update, that
                   ;; happens when a message that was missing a reply had the reply
                   ;; coming through, in which case we just insert the new message
                   {:db (assoc-in db [:messages chat-id message-id] message)}
                   (fx/merge cofx
                             (add-received-message message)
                             (update-unviewed-count message)
                             (chat-model/join-time-messages-checked chat-id)
                             (check-for-incoming-tx message)))))))

(defn update-unviewed-count-in-db
  [{:keys [db] :as acc} {:keys [chat-id from message-type new?]}]
  (if (or (= message-type constants/message-type-private-group-system-message)
          (= from (multiaccounts.model/current-public-key {:db db}))
          (chat-model/profile-chat? {:db db} chat-id)
          (= (:current-chat-id db) chat-id))
    acc
    (cond-> acc
        new?
        (update-in [:db :chats chat-id :unviewed-messages-count] inc))))

(defn add-timeline-message [acc chat-id message-id message]
  (-> acc
      (update-in [:db :messages chat-id] assoc message-id message)
      (update-in [:db :message-lists chat-id] message-list/add message)))

(defn reduce-js-messages [{:keys [db] :as acc} ^js message-js]
  (let [chat-id (.-chatId message-js)
        clock-value (.-clock message-js)
        message-id (.-id message-js)
        timeline-message (when (and
                                (get-in db [:pagination-info constants/timeline-chat-id :messages-initialized?])
                                (contact.db/added? db (get-in db [:chats chat-id :profile-public-key])))
                           (data-store.messages/<-rpc (types/js->clj message-js)))
        ;;add timeline message
        {:keys [db] :as acc} (if timeline-message
                               (add-timeline-message acc chat-id message-id timeline-message)
                               acc)]
    ;;ignore not opened chats and earlier clock
    (if (and (get-in db [:pagination-info chat-id :messages-initialized?])
             (not (earlier-than-deleted-at? db chat-id clock-value)))
             ;;TODO do not add older messages than in paging , only newer)
      (let [n (re-frame.interop/now)
            {:keys [transaction-hash alias replace from] :as message}
            (or timeline-message (data-store.messages/<-rpc (types/js->clj message-js)))
            _ (println "js->clj" (- (re-frame.interop/now) n))]
        (if (message-loaded? db chat-id message-id)
          ;; If the message is already loaded, it means it's an update, that
          ;; happens when a message that was missing a reply had the reply
          ;; coming through, in which case we just insert the new message
          (assoc-in acc [:db :messages chat-id message-id] message)
          (cond-> acc
            ;;add new message to db
            :always
            (update-in [:db :messages chat-id] assoc message-id message)
            :always
            (#(let [n (re-frame.interop/now)]
                (-> %
                    (update-in [:db :message-lists chat-id] message-list/add message)
                    (update-in [:db :debug :list-add-num] inc)
                    (update-in [:db :debug :list-add] + (- (re-frame.interop/now) n)))))

            ;;update counter
            :always
            (update-unviewed-count-in-db message)

            ;;conj incoming transaction for :watch-tx
            (not (string/blank? transaction-hash))
            (update :transactions conj transaction-hash)

            ;;conj sender for add-sender-to-chat-users
            (and (not (string/blank? alias))
                 ;;TODO ask Roman if its ok, because mentions/add-searchable-phrases for every message doesn't look good
                 (not (get-in db [:chats chat-id :users from])))
            (update :senders conj message)

            ;;conj replaced messages
            replace
            (update :replaced conj (get-in db [:messages chat-id replace]))

            ;;conj chat-id for join-time
            :always
            (update :chats conj chat-id))))
      acc)))

(def debug? ^boolean js/goog.DEBUG)

(defn receive-many [{:keys [db]} ^js response-js]
  (let [messages-js ^js (.splice (.-messages response-js) 0 50)
        msg-cnt (count messages-js)
        n (re-frame.interop/now)
        {:keys [db replaced chats senders transactions]}
        (reduce reduce-js-messages
                {:db (-> db
                         (assoc-in [:db :debug :list-add-num] 0)
                         (assoc-in [:db :debug :list-add] 0))
                 :replaced #{} :chats #{} :senders #{} :transactions #{}}
                messages-js)
        _ (println "reduce" (- (re-frame.interop/now) n))
        current-chat-id (:current-chat-id db)]
    ;;we want to render new messages as soon as possible so we dispatch later all other events which can be handled async
    {:utils/dispatch-later (concat [{:ms 20 :dispatch [:process-response response-js]}]
                                   (when (and current-chat-id
                                              (get chats current-chat-id)
                                              (not (chat-model/profile-chat? {:db db} current-chat-id)))
                                      [{:ms 30 :dispatch [:chat/mark-all-as-read (:current-chat-id db)]}])
                                   ;;TODO it seems we need to hide them sync
                                   #_(when (seq replaced)
                                       [:chat/hide-messages replaced])
                                   (when (seq senders)
                                     [{:ms 40 :dispatch [:chat/add-senders-to-chat-users senders]}])
                                   ;;hope there will be only a few
                                   (when (seq transactions)
                                     (for [transaction-hash transactions]
                                       {:ms 60 :dispatch [:watch-tx transaction-hash]}))
                                   (when (seq chats)
                                     [{:ms 50 :dispatch [:chat/join-times-messages-checked chats]}]))
     :db (cond-> db
                 debug?
                 (assoc-in [:signal-debug :process-messsages] msg-cnt))}))

(fx/defn join-time-messages-checked
  {:events [:chat/join-times-messages-checked]}
  [{:keys [db] :as cofx} chat-ids]
  (reduce (fn [acc chat-id]
            (cond-> acc
                    (:might-have-join-time-messages? (chat-model/get-chat cofx chat-id))
                    (update :db #(chat-model/dissoc-join-time-fields % chat-id))))
          {:db db}
          chat-ids))

(fx/defn add-senders-to-chat-users
  {:events [:chat/add-senders-to-chat-users]}
  [{:keys [db]} messages]
  (reduce (fn [acc {:keys [chat-id alias name identicon from]}]
            (update-in acc [:db :chats chat-id :users] assoc
                       from
                       (mentions/add-searchable-phrases
                        {:alias      alias
                         :name       (or name alias)
                         :identicon  identicon
                         :public-key from
                         :nickname   (get-in db [:contacts/contacts from :nickname])})))
          {:db db}
          messages))

;;;; Send message
(fx/defn update-message-status
  [{:keys [db] :as cofx} chat-id message-id status]
  (fx/merge cofx
            {:db (assoc-in db
                           [:messages chat-id message-id :outgoing-status]
                           status)}
            (data-store.messages/update-outgoing-status message-id status)))

(fx/defn resend-message
  [{:keys [db] :as cofx} chat-id message-id]
  (fx/merge cofx
            {::json-rpc/call [{:method (json-rpc/call-ext-method "reSendChatMessage")
                               :params [message-id]
                               :on-success #(log/debug "re-sent message successfully")
                               :on-error #(log/error "failed to re-send message" %)}]}
            (update-message-status chat-id message-id :sending)))

(fx/defn delete-message
  "Deletes chat message, rebuild message-list"
  {:events [:chat.ui/delete-message]}
  [{:keys [db] :as cofx} chat-id message-id]
  (fx/merge cofx
            {:db            (update-in db [:messages chat-id] dissoc message-id)}
            (data-store.messages/delete-message message-id)
            (rebuild-message-list chat-id)))

(fx/defn send-message
  [{:keys [db now] :as cofx} message]
  (protocol/send-chat-messages cofx [message]))

(fx/defn send-messages
  [{:keys [db now] :as cofx} messages]
  (protocol/send-chat-messages cofx messages))

(fx/defn toggle-expand-message
  [{:keys [db]} chat-id message-id]
  {:db (update-in db [:messages chat-id message-id :expanded?] not)})
