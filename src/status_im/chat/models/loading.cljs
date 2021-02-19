(ns status-im.chat.models.loading
  (:require [re-frame.core :as re-frame]
            [status-im.constants :as constants]
            [status-im.ui.screens.chat.state :as chat.state]
            [status-im.data-store.chats :as data-store.chats]
            [status-im.data-store.messages :as data-store.messages]
            [status-im.mailserver.core :as mailserver]
            [status-im.utils.fx :as fx]
            [status-im.chat.models.reactions :as reactions]
            [status-im.chat.models.message-list :as message-list]
            [taoensso.timbre :as log]
            [status-im.chat.models.mentions :as mentions]
            [status-im.ethereum.json-rpc :as json-rpc]))

(defn cursor->clock-value
  [^js cursor]
  (js/parseInt (.substring cursor 51 64)))

(defn clock-value->cursor [clock-value]
  (str "000000000000000000000000000000000000000000000000000" clock-value "0x0000000000000000000000000000000000000000000000000000000000000000"))

(fx/defn update-chats-in-app-db
  {:events [:chats-list/load-success]}
  [{:keys [db] :as cofx} new-chats]
  (let [old-chats (:chats db)
        chats (reduce (fn [acc {:keys [chat-id] :as chat}]
                        (assoc acc chat-id chat))
                      {}
                      new-chats)
        chats (merge old-chats chats)]
    {:db (assoc db :chats chats
                :chats/loading? false)}))

;;TODO im not sure in which cases we want to offload message and how its useful? i feel like flatlist works good with
;;big lists, we should retest
#_(fx/defn handle-chat-visibility-changed
    {:events [:chat.ui/message-visibility-changed]}
    [{:keys [db]} ^js event]
    (let [^js viewable-items (.-viewableItems event)
          ^js last-element (aget viewable-items (dec (.-length viewable-items)))]
      ;;TODO: what if we scroll and leave the chat and open another ?
      (when last-element
        (let [last-element-clock-value (:clock-value (.-item last-element))
              chat-id (:chat-id (.-item last-element))]
          (when (and last-element-clock-value
                     (get-in db [:pagination-info chat-id :messages-initialized?]))
            (let [new-messages (reduce-kv (fn [acc message-id {:keys [clock-value] :as v}]
                                            (if (<= last-element-clock-value clock-value)
                                              (assoc acc message-id v)
                                              acc))
                                          {}
                                          (get-in db [:messages chat-id]))]
              {:db (-> db
                       (assoc-in [:messages chat-id] new-messages)
                       (assoc-in [:pagination-info chat-id] {:all-loaded? false
                                                             :messages-initialized? true
                                                             :cursor (clock-value->cursor last-element-clock-value)})
                       (assoc-in [:message-lists chat-id] (message-list/add-many nil (vals new-messages))))}))))))

(fx/defn initialize-chats
  "Initialize persisted chats on startup"
  [cofx]
  (data-store.chats/fetch-chats-rpc cofx {:on-success
                                          #(re-frame/dispatch
                                            [:chats-list/load-success %])}))
(fx/defn handle-failed-loading-messages
  {:events [::failed-loading-messages]}
  [{:keys [db]} current-chat-id _ err]
  (log/error "failed loading messages" current-chat-id err)
  (when current-chat-id
    {:db (assoc-in db [:pagination-info current-chat-id :loading-messages?] false)}))

(fx/defn handle-mark-all-read-successful
  {:events [::mark-all-read-successful]}
  [{:keys [db]} chat-id]
  {:db (assoc-in db [:chats chat-id :unviewed-messages-count] 0)})

(fx/defn handle-mark-all-read
  {:events [:chat.ui/mark-all-read-pressed :chat/mark-all-as-read]}
  [_ chat-id]
  {::json-rpc/call [{:method     (json-rpc/call-ext-method "markAllRead")
                     :params     [chat-id]
                     :on-success #(re-frame/dispatch [::mark-all-read-successful chat-id])}]})

(fx/defn messages-loaded
  "Loads more messages for current chat"
  {:events [::messages-loaded]}
  [{db :db :as cofx}
   chat-id
   session-id
   {:keys [cursor messages]}]
  (when-not (and (get-in db [:pagination-info chat-id :messages-initialized?])
                 (not= session-id
                       (get-in db [:pagination-info chat-id :messages-initialized?])))
    (let [already-loaded-messages      (get-in db [:messages chat-id])
          users                        (get-in db [:chats chat-id :users] {})
          ;; We remove those messages that are already loaded, as we might get some duplicates
          {:keys [all-messages
                  new-messages
                  last-clock-value
                  unviewed-message-ids
                  users]}
          (reduce (fn [{:keys [last-clock-value all-messages] :as acc}
                       {:keys [clock-value message-id alias name identicon from]
                        :as message}]
                    (let [nickname (get-in db [:contacts/contacts from :nickname])]
                      (cond-> acc
                        (and alias (not= alias ""))
                        (update :users assoc from
                                (mentions/add-searchable-phrases
                                 {:alias      alias
                                  :name       (or name alias)
                                  :identicon  identicon
                                  :public-key from
                                  :nickname   nickname}))
                        (or (nil? last-clock-value)
                            (> last-clock-value clock-value))
                        (assoc :last-clock-value clock-value)

                        (nil? (get all-messages message-id))
                        (update :new-messages conj message)

                        :always
                        (update :all-messages assoc message-id message))))
                  {:all-messages         already-loaded-messages
                   :users                users
                   :new-messages         []}
                  messages)]
      (fx/merge cofx
                {:db (-> db
                         (assoc-in [:pagination-info chat-id :cursor-clock-value] (when (seq cursor) (cursor->clock-value cursor)))
                         (assoc-in [:chats chat-id :users] users)
                         (assoc-in [:pagination-info chat-id :loading-messages?] false)
                         (assoc-in [:messages chat-id] all-messages)
                         (update-in [:message-lists chat-id] message-list/add-many new-messages)
                         (assoc-in [:pagination-info chat-id :cursor] cursor)
                         (assoc-in [:pagination-info chat-id :all-loaded?]
                                   (empty? cursor)))}
                (handle-mark-all-read chat-id)))))

(fx/defn load-more-messages
  {:events [:chat.ui/load-more-messages]}
  [{:keys [db] :as cofx} chat-id]
  (when-let [session-id (get-in db [:pagination-info chat-id :messages-initialized?])]
    (when-not (or (get-in db [:pagination-info chat-id :all-loaded?])
                  (get-in db [:pagination-info chat-id :loading-messages?]))
      (let [cursor (get-in db [:pagination-info chat-id :cursor])
            load-messages-fx (data-store.messages/messages-by-chat-id-rpc
                              chat-id
                              cursor
                              constants/default-number-of-messages
                              #(re-frame/dispatch [::messages-loaded chat-id session-id %])
                              #(re-frame/dispatch [::failed-loading-messages chat-id session-id %]))]
        (fx/merge cofx
                  load-messages-fx
                  (reactions/load-more-reactions cursor chat-id)
                  (mailserver/load-gaps-fx chat-id))))))

(fx/defn load-messages
  {:events [:load-messages]}
  [{:keys [db now] :as cofx} chat-id]
  (if-not (get-in db [:pagination-info chat-id :messages-initialized?])
    (do
     ; reset chat first-not-visible-items state
      (chat.state/reset)
      (fx/merge cofx
                {:db (assoc-in db [:pagination-info chat-id :messages-initialized?] now)}
                (handle-mark-all-read chat-id)
                (load-more-messages chat-id)))
    ;; We mark messages as seen in case we received them while on a different tab
    (handle-mark-all-read cofx chat-id)))
