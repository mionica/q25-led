package com.duc1607.q25led

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Service that listens for notifications from apps and controls the LED accordingly
 */
class NotificationListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var isBlinking = false
    private var currentBlinkColor: String? = null
    private var blinkRunnable: Runnable? = null
    private val activeNotifications = mutableSetOf<String>() // Set of package names with active notifications
    private var wakeLock: PowerManager.WakeLock? = null

    // Blink configuration
    private val blinkDuration = 200L // LED on duration in ms
    private val blinkInterval = 2000L // Total interval between blinks in ms

    override fun onCreate() {
        super.onCreate()
        // Initialize wake lock to keep CPU running during screen off
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Q25Led:NotificationWakeLock"
        )
        wakeLock?.setReferenceCounted(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        // Get the package name of the app that posted the notification
        val packageName = sbn.packageName

        // Check if this app is in our saved list and get its color
        val color = getAppColor(packageName)

        // If the app is in our list, start blinking the LED with its color
        if (color != null) {
            // Track this notification
            activeNotifications.add(packageName)

            // Start blinking with this color
            startBlinking(color)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)

        val packageName = sbn.packageName

        // Check if we're tracking notifications for this package
        if (activeNotifications.contains(packageName)) {
            // This was the last notification from this app, remove it
            activeNotifications.remove(packageName)

            // Check if we should stop blinking
            if (activeNotifications.isEmpty()) {
                // No more notifications from any tracked app, stop blinking
                stopBlinking()
            } else {
                // We still have notifications from other apps
                // Find the next app with notifications and switch to its color
                switchToNextNotificationColor()
            }
        }
    }

    override fun onDestroy() {
        stopBlinking()
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Get the saved color for an app package name
     */
    private fun getAppColor(packageName: String): String? {
        val sharedPrefs = getSharedPreferences("q25_led_prefs", Context.MODE_PRIVATE)
        return sharedPrefs.getString(packageName, null)
    }

    /**
     * Switch to the next app's notification color
     */
    private fun switchToNextNotificationColor() {
        // Find any app with active notifications
        val nextApp = activeNotifications.firstOrNull() ?: return
        val nextColor = getAppColor(nextApp) ?: return

        // Switch to this color without stopping the current blink cycle
        currentBlinkColor = nextColor
    }

    /**
     * Start blinking the LED with the specified color
     */
    private fun startBlinking(color: String) {
        // Acquire wake lock to ensure CPU stays active
        acquireWakeLock()

        // If already blinking, just update the color
        if (isBlinking) {
            currentBlinkColor = color
            return
        }

        // Start new blinking sequence
        currentBlinkColor = color
        isBlinking = true

        // Create a new runnable for the blink sequence
        blinkRunnable = object : Runnable {
            override fun run() {
                if (!isBlinking) return

                // Turn LED on with the color
                ledControl(currentBlinkColor ?: "OFF")

                // Schedule turning off after the blink duration
                handler.postDelayed({
                    if (isBlinking) {
                        // Turn LED off
                        ledControl("OFF")
                    }
                }, blinkDuration)

                // Schedule next blink cycle
                handler.postDelayed(this, blinkInterval)
            }
        }

        // Start the blinking immediately
        handler.post(blinkRunnable!!)
    }

    /**
     * Stop the LED from blinking
     */
    private fun stopBlinking() {
        isBlinking = false
        currentBlinkColor = null
        blinkRunnable?.let { handler.removeCallbacks(it) }
        blinkRunnable = null

        // Ensure LED is turned off
        ledControl("OFF")

        // Release the wake lock since we're no longer blinking
        releaseWakeLock()
    }

    /**
     * Acquire wake lock to keep CPU active during blinking
     */
    private fun acquireWakeLock() {
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire(10*60*1000L) // 10 minutes timeout as safety
            }
        }
    }

    /**
     * Release wake lock when no longer needed
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }
}
