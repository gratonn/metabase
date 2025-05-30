(ns metabase.product-feedback.task.follow-up-emails
  "Tasks which follow up with Metabase users."
  (:require
   [clojurewerkz.quartzite.jobs :as jobs]
   [clojurewerkz.quartzite.schedule.cron :as cron]
   [clojurewerkz.quartzite.triggers :as triggers]
   [java-time.api :as t]
   [metabase.analytics.core :as analytics]
   [metabase.channel.email.messages :as messages]
   [metabase.channel.settings :as channel.settings]
   [metabase.product-feedback.settings :as product-feedback.settings]
   [metabase.task.core :as task]
   [metabase.util.date-2 :as u.date]
   [metabase.util.log :as log]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(defn- send-follow-up-email!
  "Send an email to the instance admin following up on their experience with Metabase thus far."
  []
  ;; we need access to email AND the instance must be opted into anonymous tracking AND have surveys enabled. Make sure
  ;; email hasn't been sent yet
  (when (and (channel.settings/email-configured?)
             (analytics/anon-tracking-enabled)
             (channel.settings/surveys-enabled)
             (not (product-feedback.settings/follow-up-email-sent)))
    ;; grab the oldest admins email address (likely the user who created this MB instance), that's who we'll send to
    ;; TODO - Does it make to send to this user instead of `(system/admin-email)`?
    (when-let [admin (t2/select-one :model/User :is_superuser true, :is_active true, {:order-by [:date_joined]})]
      (try
        (messages/send-follow-up-email! (:email admin))
        (catch Throwable e
          (log/error e "Problem sending follow-up email:"))
        (finally
          (product-feedback.settings/follow-up-email-sent! true))))))

(defn- instance-creation-timestamp
  "The date this Metabase instance was created. We use the `:date_joined` of the first `User` to determine this."
  ^java.time.temporal.Temporal []
  (t2/select-one-fn :date_joined :model/User, {:order-by [[:date_joined :asc]]}))

(task/defjob ^{:doc "Sends out a general 2 week email follow up email"} FollowUpEmail [_]
  ;; if we've already sent the follow-up email then we are done
  (when-not (product-feedback.settings/follow-up-email-sent)
    ;; figure out when we consider the instance created
    (when-let [instance-created (instance-creation-timestamp)]
      ;; we need to be 2+ weeks from creation to send the follow up
      (when (u.date/older-than? instance-created (t/weeks 2))
        (send-follow-up-email!)))))

(def ^:private follow-up-emails-job-key     "metabase.task.follow-up-emails.job")
(def ^:private follow-up-emails-trigger-key "metabase.task.follow-up-emails.trigger")

(defmethod task/init! ::SendFollowUpEmails [_]
  (let [job     (jobs/build
                 (jobs/of-type FollowUpEmail)
                 (jobs/with-identity (jobs/key follow-up-emails-job-key)))
        trigger (triggers/build
                 (triggers/with-identity (triggers/key follow-up-emails-trigger-key))
                 (triggers/start-now)
                 (triggers/with-schedule
                   ;; run once a day
                  (cron/cron-schedule "0 0 12 * * ? *")))]
    (task/schedule-task! job trigger)))
