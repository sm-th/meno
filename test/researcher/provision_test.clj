(ns researcher.provision-test
  (:require [clojure.test :refer [deftest is]]
            [provision :as provision]))

(deftest resolves-conversational-bot-user-id
  (let [subscriptions (atom [])
        adapter {:list-streams (constantly ["research"])
                 :list-bots (constantly [{:full_name "Researcher"
                                          :username "researcher-bot@example.com"
                                          :api_key "secret"}])
                 :list-users (constantly [{:user_id 10
                                           :email "researcher-bot@example.com"}])
                 :create-bot! (fn [_] (throw (ex-info "unexpected bot creation" {})))
                 :subscribe! (fn [email streams]
                               (swap! subscriptions conj [email streams]))}
        identities (provision/reconcile!
                    adapter
                    {:researcher {:chat "Researcher" :streams ["research"]}})]
    (is (= {:email "researcher-bot@example.com"
            :api-key "secret"
            :user-id 10}
           (:researcher identities)))
    (is (= [["researcher-bot@example.com" ["research"]]] @subscriptions))))
