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

(defn- message-loaded?
  [db chat-id message-id]
  (get-in db [:messages chat-id message-id]))

(defn- earlier-than-deleted-at?
  [db chat-id clock-value]
  (>= (get-in db [:chats chat-id :deleted-at-clock-value]) clock-value))

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

(fx/defn join-times-messages-checked
  "The key :might-have-join-time-messages? in public chats signals that
  the public chat is freshly (re)created and requests for messages to the
  mailserver for the topic has not completed yet. Likewise, the key
  :join-time-mail-request-id is associated a little bit after, to signal that
  the request to mailserver was a success. When request is signalled complete
  by mailserver, corresponding event :chat.ui/join-times-messages-checked
  dissociates these two fileds via this function, thereby signalling that the
  public chat is not fresh anymore."
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
      (let [{:keys [transaction-hash alias replace from] :as message}
            (or timeline-message (data-store.messages/<-rpc (types/js->clj message-js)))]
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
            (update-in [:db :message-lists chat-id] message-list/add message)

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

(defn receive-many [{:keys [db]} ^js response-js]
  (let [messages-js ^js (.splice (.-messages response-js) 0 10)
        {:keys [db _ chats senders transactions]}
        (reduce reduce-js-messages
                {:db db :replaced #{} :chats #{} :senders #{} :transactions #{}}
                messages-js)
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
     :db db}))

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
