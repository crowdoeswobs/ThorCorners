package dev.thorcorners

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.roundToInt

/**
 * Draws rounded corners on every screen the Thor has.
 *
 * Why an accessibility service: it's the only way a normal app can draw a window that is
 *  1. fully opaque (true black),
 *  2. above the status bar and navigation bar, and
 *  3. still lets touches pass straight through.
 * Regular "display over other apps" overlays fail 2 and 3: since Android 12 the system drops
 * any touch that lands on an opaque untrusted overlay, even one marked not-touchable. That is
 * what made Curvo's overlay act like a hitbox. Accessibility overlays are trusted, so touches
 * go through to whatever is underneath.
 *
 * This service reads nothing: it subscribes to no useful events and can't see window content.
 *
 * Each screen gets its own window context (createWindowContext(display, ...)), so the top
 * screen (id 0) and the Thor's bottom screen (id 4) each get their own four corner windows.
 */
class CornerService : AccessibilityService() {

    private lateinit var displayManager: DisplayManager
    private lateinit var appWatcher: ForegroundAppWatcher
    private val overlays = mutableMapOf<Int, DisplayOverlay>()
    private val handler = Handler(Looper.getMainLooper())
    private var connected = false

    // Inputs that decide visibility.
    private var batteryLevel = -1
    private var charging = false
    private var openApps: Set<String> = emptySet()
    private var screenOn = true

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh(displayId)
        override fun onDisplayRemoved(displayId: Int) = removeOverlay(displayId)
        override fun onDisplayChanged(displayId: Int) = refresh(displayId)
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        updateAppWatcher()
        refreshAll()
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val newLevel = if (level >= 0 && scale > 0) level * 100 / scale else -1
                    val newCharging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    if (newLevel != batteryLevel || newCharging != charging) {
                        batteryLevel = newLevel
                        charging = newCharging
                        refreshAll()
                    }
                }
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; updateAppWatcher() }
                Intent.ACTION_SCREEN_ON -> { screenOn = true; updateAppWatcher() }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        applyStartOnBoot()
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, handler)
        appWatcher = ForegroundAppWatcher(this) { apps ->
            openApps = apps
            refreshAll()
        }
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefsListener)
        Prefs.rules(this).registerOnSharedPreferenceChangeListener(prefsListener)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(systemReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        updateAppWatcher()
        refreshAll()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (!connected) return
        connected = false
        displayManager.unregisterDisplayListener(displayListener)
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        Prefs.rules(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        runCatching { unregisterReceiver(systemReceiver) }
        appWatcher.stop()
        overlays.keys.toList().forEach(::removeOverlay)
    }

    /**
     * Accessibility services always restart on boot. If "Start on boot" is off, the first
     * connection after a reboot switches the corners off instead (the user can turn them on).
     */
    private fun applyStartOnBoot() {
        val bootCount = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (bootCount != Prefs.lastBootCount(this)) {
            Prefs.setLastBootCount(this, bootCount)
            if (!Prefs.startOnBoot(this)) Prefs.setRunning(this, false)
        }
    }

    /** Only poll for open apps when it matters: rules exist, permission granted, screen on. */
    private fun updateAppWatcher() {
        val needed = screenOn && Prefs.isRunning(this) &&
            Prefs.allAppRules(this).isNotEmpty() &&
            ForegroundAppWatcher.hasPermission(this)
        if (needed) appWatcher.start() else appWatcher.stop()
    }

    private fun isLowBattery(): Boolean =
        Prefs.lowBatteryEnabled(this) && !charging &&
            batteryLevel in 0..Prefs.lowBatteryLevel(this)

    private fun hiddenByApps(displayId: Int): Boolean {
        val flag = if (Prefs.isTopScreen(displayId)) Prefs.RULE_HIDE_TOP else Prefs.RULE_HIDE_BOTTOM
        return openApps.any { Prefs.appRule(this, it) and flag != 0 }
    }

    private fun shouldShow(displayId: Int) =
        Prefs.isRunning(this) && Prefs.isDisplayEnabled(this, displayId) &&
            !isLowBattery() && !hiddenByApps(displayId)

    private fun refreshAll() {
        if (!connected) return
        val ids = displayManager.displays.map { it.displayId }.toSet()
        overlays.keys.filter { it !in ids }.forEach(::removeOverlay)
        ids.forEach(::refresh)
    }

    private fun refresh(displayId: Int) {
        if (!connected) return
        val display = displayManager.getDisplay(displayId)
        if (display == null || !shouldShow(displayId)) {
            removeOverlay(displayId)
            return
        }
        val radiusDp = Prefs.radiusDp(this, displayId)
        val overlay = overlays[displayId] ?: try {
            DisplayOverlay(this, display, radiusDp).also { overlays[displayId] = it }
        } catch (e: Exception) {
            Log.e(TAG, "Could not add overlay on display $displayId (${display.name})", e)
            return
        }
        overlay.setRadiusDp(radiusDp)
    }

    private fun removeOverlay(displayId: Int) {
        overlays.remove(displayId)?.remove()
    }

    /** The four corner windows on one display. */
    private class DisplayOverlay(service: AccessibilityService, display: Display, radiusDp: Int) {
        private val context: Context =
            service.createWindowContext(display, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        private val windowManager = context.getSystemService(WindowManager::class.java)
        private val density = context.resources.displayMetrics.density
        private val views = Corner.entries.associateWith { CornerView(context, it) }
        private var radiusPx = (radiusDp * density).roundToInt()

        init {
            views.forEach { (corner, view) ->
                view.visibility = if (radiusPx > 0) View.VISIBLE else View.GONE
                windowManager.addView(view, layoutParams(corner, radiusPx, display.displayId))
            }
            Log.i(TAG, "Corners added on display ${display.displayId} (${display.name})")
        }

        fun setRadiusDp(dp: Int) {
            val px = (dp * density).roundToInt()
            if (px == radiusPx) return
            radiusPx = px
            views.forEach { (_, view) ->
                val lp = view.layoutParams as WindowManager.LayoutParams
                lp.width = px
                lp.height = px
                windowManager.updateViewLayout(view, lp)
                view.visibility = if (px > 0) View.VISIBLE else View.GONE
            }
        }

        fun remove() {
            views.values.forEach { runCatching { windowManager.removeViewImmediate(it) } }
        }

        private fun layoutParams(corner: Corner, size: Int, displayId: Int) =
            WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // NOT_TOUCHABLE: touches go to whatever is underneath, never to us.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = corner.gravity
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
                title = "ThorCorners-d$displayId-$corner"
            }
    }

    companion object {
        private const val TAG = "ThorCorners"

        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, CornerService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
