package com.duc1607.q25led

import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Service that listens for notifications from apps and controls the LED accordingly
 */
class NotificationListener : NotificationListenerService() {

    companion object {
        init {
            try {
                System.loadLibrary("q25led")
            } catch (e: Exception) {
                Log.e("q25led", "System.loadLibrary() failed!")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isBlinking = false
    private var currentBlinkColor: String? = null
    private var blinkRunnable: Runnable? = null

    // Active notifications tracked per package as a set of StatusBarNotification keys.
    // A package-name-only set cannot distinguish an app's individual notifications, so
    // removal of any one notification would clear the whole package prematurely. Keying
    // on sbn.key defers stop until a package's final notification is removed.
    // Iteration order is recency order: a package is re-inserted at the tail each time one
    // of its notifications is posted, so the last entry is the most recently notified.
    private val activeByPackage = LinkedHashMap<String, MutableSet<String>>()

    private var wakeLock: PowerManager.WakeLock? = null

    // Preference keys and defaults (must match SettingsScreen)
    private val prefName = "q25_led_prefs"
    private val keyBlinkDuration = "blink_duration_ms"
    private val keyBlinkInterval = "blink_interval_ms"
    private val keyIgnoreDnd = "ignore_dnd"
    private val keyMultiBlink = "multi_blink"
    private val defaultBlinkDuration = 200 // ms (matches Settings default)
    private val defaultBlinkInterval = 1000 // ms

    // Off period between consecutive blinks within one multi-blink cycle. Shorter than the
    // user-configured blink interval so the group reads as one cycle, and non-zero so the
    // blinks remain distinct from the continuous rainbow sweep.
    private val multiBlinkGapMs = 150L

    private val rainbowColors = listOf("RED", "ORANGE", "YELLOW", "GREEN", "CYAN", "BLUE", "MAGENTA")

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
        val color = getAppColor(packageName) ?: return

        // Track this specific notification instance by its key. Repeated posts of the
        // same key (in-place updates) are de-duplicated by the set. The package is removed
        // and re-inserted so that it moves to the tail of the recency order.
        val keys = activeByPackage.remove(packageName) ?: mutableSetOf()
        keys.add(sbn.key)
        activeByPackage[packageName] = keys

        // Start blinking with this color
        startBlinking(color)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)

        val packageName = sbn.packageName

        // Only act on packages currently tracked.
        val keys = activeByPackage[packageName] ?: return

        // Remove the specific notification instance; drop the package only when empty.
        keys.remove(sbn.key)
        if (keys.isEmpty()) {
            activeByPackage.remove(packageName)
        }

        if (activeByPackage.isEmpty()) {
            // No more notifications from any tracked app, stop blinking
            stopBlinking()
        } else {
            // Notifications from other tracked apps remain; switch to one of their colors
            switchToNextNotificationColor()
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
        val sharedPrefs = getSharedPreferences(prefName, Context.MODE_PRIVATE)
        return sharedPrefs.getString(packageName, null)
    }

    /**
     * Load blink duration from preferences (ms)
     */
    private fun getBlinkDurationMs(): Long {
        val prefs = getSharedPreferences(prefName, Context.MODE_PRIVATE)
        return prefs.getInt(keyBlinkDuration, defaultBlinkDuration).toLong()
    }

    /**
     * Load blink interval from preferences (ms)
     */
    private fun getBlinkIntervalMs(): Long {
        val prefs = getSharedPreferences(prefName, Context.MODE_PRIVATE)
        return prefs.getInt(keyBlinkInterval, defaultBlinkInterval).toLong()
    }

    /**
     * Whether each cycle blinks once per waiting notification color (true) or once for the
     * most recent notification only (false)
     */
    private fun getMultiBlink(): Boolean {
        val prefs = getSharedPreferences(prefName, Context.MODE_PRIVATE)
        return prefs.getBoolean(keyMultiBlink, false)
    }

    /**
     * Distinct colors of all packages with active notifications, most recent first.
     * Packages sharing a color contribute a single entry.
     */
    private fun getActiveColorsNewestFirst(): List<String> {
        return activeByPackage.keys.reversed().mapNotNull { getAppColor(it) }.distinct()
    }

    /**
     * Whether to ignore DND and blink anyway
     */
    private fun getIgnoreDnd(): Boolean {
        val prefs = getSharedPreferences(prefName, Context.MODE_PRIVATE)
        return prefs.getBoolean(keyIgnoreDnd, false)
    }

    /**
     * Detect if system Do Not Disturb is currently active
     */
    private fun isDoNotDisturbOn(): Boolean {
        return try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            when (nm.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_ALL,
                NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> false
                else -> true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Switch to a remaining tracked app's notification color
     */
    private fun switchToNextNotificationColor() {
        // Select the most recently notified package that still has active notifications
        val nextApp = activeByPackage.keys.lastOrNull() ?: return
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

                // Read current settings each cycle
                // onMs = how long LED stays ON per blink
                // offMs = interval between blinks (LED OFF time)
                val onMs = getBlinkDurationMs().coerceAtLeast(50L)
                val offMs = getBlinkIntervalMs().coerceAtLeast(50L)

                // Respect DND unless user opted to ignore it
                val shouldIgnoreDnd = getIgnoreDnd()
                val dndOn = isDoNotDisturbOn()
                if (dndOn && !shouldIgnoreDnd) {
                    // Ensure LED is off while in DND
                    ledControl("OFF")
                    // Check again after the off interval
                    handler.postDelayed(this, offMs)
                    return
                }

                // Multi-blink: one blink per distinct waiting color, followed by the usual
                // off interval. A single distinct color is handled by the single-blink path,
                // which yields identical timing.
                val multiColors = if (getMultiBlink()) getActiveColorsNewestFirst() else emptyList()

                if (multiColors.size > 1) {
                    val blinksEndMs = scheduleMultiBlink(multiColors, onMs)

                    // Schedule next cycle after the last blink + OFF interval
                    handler.postDelayed(this, blinksEndMs + offMs)
                } else if (currentBlinkColor == "RAINBOW") {
                    // Rainbow cycle
                    var delay = 0L
                    for (c in rainbowColors) {
                        handler.postDelayed({
                            if (isBlinking && currentBlinkColor == "RAINBOW") {
                                ledControl(c)
                            }
                        }, delay)
                        delay += onMs
                    }

                    // Schedule next blink cycle
                    handler.postDelayed(this, delay + onMs)
                } else {
                    // Turn LED on with the color
                    ledControl(currentBlinkColor ?: "ON")

                    // Schedule turning off after the ON duration
                    handler.postDelayed({
                        if (isBlinking) {
                            ledControl("OFF")
                        }
                    }, onMs)

                    // Schedule next blink cycle after ON + OFF
                    handler.postDelayed(this, onMs + offMs)
                }
            }
        }

        // Start the blinking immediately
        handler.post(blinkRunnable!!)
    }

    /**
     * Schedule one blink per color, separated by multiBlinkGapMs of LED off time. A RAINBOW
     * entry is expanded to the full rainbow sweep as a single blink group.
     * @return Time offset (ms) at which the final blink ends
     */
    private fun scheduleMultiBlink(colors: List<String>, onMs: Long): Long {
        var offsetMs = 0L
        for ((index, color) in colors.withIndex()) {
            if (index > 0) offsetMs += multiBlinkGapMs

            val steps = if (color == "RAINBOW") rainbowColors else listOf(color)
            for (step in steps) {
                handler.postDelayed({
                    if (isBlinking) {
                        ledControl(step)
                    }
                }, offsetMs)
                offsetMs += onMs
            }

            // Turn LED off at the end of this blink group
            handler.postDelayed({
                if (isBlinking) {
                    ledControl("OFF")
                }
            }, offsetMs)
        }
        return offsetMs
    }

    /**
     * Stop the LED from blinking
     */
    private fun stopBlinking() {
        isBlinking = false
        currentBlinkColor = null
        // The handler is dedicated to this service; clearing all messages also discards
        // the individually scheduled blink steps, which cannot be removed one by one.
        handler.removeCallbacksAndMessages(null)
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
