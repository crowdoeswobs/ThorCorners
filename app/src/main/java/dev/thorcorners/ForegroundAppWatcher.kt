package dev.thorcorners

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process

/**
 * Tracks which apps currently have a resumed (visible, active) activity, using usage access.
 * On the Thor both screens can have an active app at the same time, so this is a *set*.
 *
 * Usage events don't say which screen an app is on, so per-app rules are
 * "while this app is open, hide corners on top / bottom / both".
 */
class ForegroundAppWatcher(
    private val context: Context,
    private val onChange: (Set<String>) -> Unit,
) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val resumed = mutableSetOf<String>() // "package/activity"
    private var lastQuery = 0L
    private var lastReported: Set<String> = emptySet()
    private var running = false

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            readEvents()
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        resumed.clear()
        // Look back a day so an app opened long ago that is still on screen is picked up.
        lastQuery = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        poll.run()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(poll)
        resumed.clear()
        report()
    }

    private fun readEvents() {
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastQuery, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val key = "${e.packageName}/${e.className}"
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> resumed += key
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> resumed -= key
            }
        }
        lastQuery = now
        report()
    }

    private fun report() {
        val pkgs = resumed.mapTo(mutableSetOf()) { it.substringBefore('/') }
        if (pkgs != lastReported) {
            lastReported = pkgs
            onChange(pkgs)
        }
    }

    companion object {
        private const val POLL_MS = 1000L

        fun hasPermission(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            return appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        }
    }
}
