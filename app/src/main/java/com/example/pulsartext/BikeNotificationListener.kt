package com.example.pulsartext

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Optional: listens to real phone notifications (SMS, WhatsApp, etc.) so you
 * could later auto-send a short custom string when, say, a message arrives.
 *
 * Not wired to the Bluetooth service yet — this is a starting point.
 * The user must manually enable this listener under
 * Settings > Apps > Special access > Notification access.
 */
class BikeNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Example hook point:
        // val title = sbn.notification.extras.getString("android.title") ?: ""
        // if (sbn.packageName == "com.whatsapp") {
        //     // send something to the bike, e.g. "MSG"
        // }
    }
}
