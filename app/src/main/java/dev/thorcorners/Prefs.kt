package dev.thorcorners

import android.content.Context
import android.content.SharedPreferences
import android.view.Display

/** All settings live here. The overlay service listens for changes, so edits apply live. */
object Prefs {
    private const val FILE = "corners"
    private const val RULES_FILE = "app_rules"
    private const val KEY_RUNNING = "running"
    private const val KEY_BOOT_COUNT = "last_boot_count"

    const val MAX_RADIUS_DP = 80
    const val DEFAULT_LOW_BATTERY_LEVEL = 15
    const val MIN_LOW_BATTERY_LEVEL = 5
    const val MAX_LOW_BATTERY_LEVEL = 50

    // Per-app rule values are bit flags: which screens lose their corners while the app is open.
    const val RULE_NONE = 0
    const val RULE_HIDE_TOP = 1
    const val RULE_HIDE_BOTTOM = 2
    const val RULE_HIDE_BOTH = RULE_HIDE_TOP or RULE_HIDE_BOTTOM

    fun get(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun rules(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(RULES_FILE, Context.MODE_PRIVATE)

    fun isTopScreen(displayId: Int) = displayId == Display.DEFAULT_DISPLAY

    // --- running state (not a setting: survives "reset to defaults") ---
    // The master on/off switch. On by default, so enabling the accessibility service shows corners.
    fun isRunning(context: Context) = get(context).getBoolean(KEY_RUNNING, true)
    fun setRunning(context: Context, value: Boolean) =
        get(context).edit().putBoolean(KEY_RUNNING, value).apply()

    // Used to spot the first service start after a reboot (for "Start on boot").
    fun lastBootCount(context: Context) = get(context).getInt(KEY_BOOT_COUNT, -1)
    fun setLastBootCount(context: Context, value: Int) =
        get(context).edit().putInt(KEY_BOOT_COUNT, value).apply()

    // --- per-display: top screen = id 0, the Thor's bottom screen = id 4 ---
    // Defaults: top screen on at 15 dp, bottom screen off at 20 dp.
    fun defaultEnabled(displayId: Int) = isTopScreen(displayId)
    fun defaultRadiusDp(displayId: Int) = if (isTopScreen(displayId)) 15 else 20

    fun isDisplayEnabled(context: Context, displayId: Int) =
        get(context).getBoolean("display_${displayId}_on", defaultEnabled(displayId))

    fun setDisplayEnabled(context: Context, displayId: Int, value: Boolean) =
        get(context).edit().putBoolean("display_${displayId}_on", value).apply()

    fun radiusDp(context: Context, displayId: Int) =
        get(context).getInt("display_${displayId}_radius", defaultRadiusDp(displayId))

    fun setRadiusDp(context: Context, displayId: Int, value: Int) =
        get(context).edit().putInt("display_${displayId}_radius", value).apply()

    // --- start on boot ---
    fun startOnBoot(context: Context) = get(context).getBoolean("start_on_boot", true)
    fun setStartOnBoot(context: Context, value: Boolean) =
        get(context).edit().putBoolean("start_on_boot", value).apply()

    // --- low battery ---
    fun lowBatteryEnabled(context: Context) = get(context).getBoolean("low_battery_on", false)
    fun setLowBatteryEnabled(context: Context, value: Boolean) =
        get(context).edit().putBoolean("low_battery_on", value).apply()

    fun lowBatteryLevel(context: Context) =
        get(context).getInt("low_battery_level", DEFAULT_LOW_BATTERY_LEVEL)

    fun setLowBatteryLevel(context: Context, value: Int) =
        get(context).edit().putInt("low_battery_level", value).apply()

    // --- per-app rules ---
    fun appRule(context: Context, pkg: String) = rules(context).getInt(pkg, RULE_NONE)

    fun setAppRule(context: Context, pkg: String, rule: Int) = rules(context).edit().apply {
        if (rule == RULE_NONE) remove(pkg) else putInt(pkg, rule)
    }.apply()

    fun allAppRules(context: Context): Map<String, Int> =
        rules(context).all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()

    /** Wipes every setting and per-app rule. Whether the corners are currently on is kept. */
    fun resetToDefaults(context: Context) {
        val running = isRunning(context)
        val bootCount = lastBootCount(context)
        get(context).edit().clear()
            .putBoolean(KEY_RUNNING, running)
            .putInt(KEY_BOOT_COUNT, bootCount)
            .apply()
        rules(context).edit().clear().apply()
    }
}
