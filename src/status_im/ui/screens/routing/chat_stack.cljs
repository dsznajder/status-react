(ns status-im.ui.screens.routing.chat-stack
  (:require [status-im.ui.screens.routing.core :as navigation]
            [status-im.ui.screens.home.views :as home]
            [status-im.ui.screens.chat.views :as chat]
            [status-im.ui.screens.group.views :as group]
            [status-im.ui.screens.referrals.public-chat :as referrals.public-chat]
            [status-im.ui.screens.communities.views :as communities]
            [status-im.ui.screens.profile.group-chat.views :as profile.group-chat]
            [status-im.ui.components.tabbar.styles :as tabbar.styles]
            [status-im.ui.screens.stickers.views :as stickers]
            [reagent.core :as reagent]
            [status-im.ui.components.react :as react]))

(defonce stack (navigation/create-stack))
(defonce group-stack (navigation/create-stack))

(defn chat-chat-stack []
  [stack {:initial-route-name :chat
          :header-mode        :none
          :detachInactiveScreens false}
   [{:name      :referral-enclav
     :component referrals.public-chat/view}
    {:name       :communities
     :transition :presentation-ios
     :insets     {:bottom true}
     :component  communities/communities}
    {:name       :community
     :transition :presentation-ios
     :insets     {:bottom true}
     :component  communities/community}
    {:name      :chat
     :component chat/chat}
    {:name      :group-chat-profile
     :insets    {:top false}
     :component profile.group-chat/group-chat-profile}
    {:name      :group-chat-invite
     :component profile.group-chat/group-chat-invite}
    {:name      :stickers
     :component stickers/packs}
    {:name      :stickers-pack
     :component stickers/pack}]])

(defonce bottom-tabs (navigation/create-bottom-tabs))

(defn tabbar [_]
  (reagent/as-element
   [react/view]))

(defn chat-stack []
  [bottom-tabs {:initial-route-name :home
                :header-mode        :none
                :tab-bar            tabbar}
   [{:name      :home
     :style     {:padding-bottom tabbar.styles/tabs-diff}
     :component home/home}
    {:name      :chat-chat-stack
     :insets    {:top false}
     :component chat-chat-stack}]])

(defn new-group-chat []
  [group-stack {:header-mode        :none
                :initial-route-name :contact-toggle-list}
   [{:name      :contact-toggle-list
     :insets    {:top    false
                 :bottom true}
     :component group/contact-toggle-list}
    {:name      :new-group
     :insets    {:top    false
                 :bottom true}
     :component group/new-group}]])
