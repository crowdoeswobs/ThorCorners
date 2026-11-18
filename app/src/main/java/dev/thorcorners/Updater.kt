package dev.thorcorners

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Check for updates": asks GitHub for the latest release, compares it with the installed
 * version, and if it's newer downloads the attached .apk and hands it to Android's installer.
 *
 * The app only goes online here, when the user taps the button. Nothing runs in the background.
 * Android verifies the download is signed with the same key as the installed app before updating.
 */
class Updater(private val activity: Activity) {

    private class Release(
        val version: String,
        val notes: String,
        val apkUrl: String?,
        val apkSize: Long,
    )

    private class NoReleases : Exception()

    fun check() {
        val waiting = progressDialog("Checking for updates…", indeterminate = true)
        Thread {
            val result = runCatching { fetchLatest() }
            activity.runOnUiThread {
                waiting.dialog.dismiss()
                if (!activity.isFinishing) handle(result)
            }
        }.start()
    }

    private fun handle(result: Result<Release>) {
        val installed = installedVersion()
        val release = result.getOrElse { e ->
            if (e is NoReleases) message("No releases yet", "There are no published releases on GitHub yet.")
            else if (e is UnknownVersion) message(
                "Couldn't read the latest version",
                "The latest release (\"${e.releaseName}\") doesn't have a version number in its tag, title or file name.",
                offerPage = true,
            )
            else message("Couldn't check for updates", "Check your internet connection and try again.\n\n(${e.message})", offerPage = true)
            return
        }
        when {
            compareVersions(release.version, installed) <= 0 ->
                message("You're up to date", "Thor Corners v$installed is the latest version.")
            release.apkUrl == null ->
                message("Update available: v${release.version}", "The release has no .apk attached yet.", offerPage = true)
            else -> AlertDialog.Builder(activity)
                .setTitle("Update available: v${release.version}")
                .setMessage(
                    "You have v$installed.\n\n" +
                        release.notes.ifBlank { "No release notes." }.take(1500)
                )
                .setPositiveButton("Download & install") { _, _ -> install(release) }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun install(release: Release) {
        // Android asks once per app before it may install updates ("Install unknown apps").
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Allow installing updates")
                .setMessage(
                    "Android needs your OK before Thor Corners can install its own updates.\n\n" +
                        "Turn on \"Allow from this source\", come back, and tap Check for updates again."
                )
                .setPositiveButton("Open settings") { _, _ ->
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val progress = progressDialog("Downloading v${release.version}…", indeterminate = release.apkSize <= 0)
        Thread {
            val result = runCatching { downloadAndCommit(release) { done -> activity.runOnUiThread { progress.set(done, release.apkSize) } } }
            activity.runOnUiThread {
                progress.dialog.dismiss()
                result.onFailure {
                    message("Update failed", "The download didn't finish. Try again.\n\n(${it.message})", offerPage = true)
                }
                // On success Android shows its own "Update this app?" screen (see UpdateResultReceiver).
            }
        }.start()
    }

    /** Streams the APK straight into an installer session, then asks Android to install it. */
    private fun downloadAndCommit(release: Release, onProgress: (Long) -> Unit) {
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(activity.packageName)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            try {
                val conn = open(release.apkUrl!!)
                conn.inputStream.use { input ->
                    session.openWrite("update.apk", 0, release.apkSize.takeIf { it > 0 } ?: -1).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            if (total - lastReport > 128 * 1024) { lastReport = total; onProgress(total) }
                        }
                        session.fsync(out)
                    }
                }
                val callback = PendingIntent.getBroadcast(
                    activity, sessionId,
                    Intent(activity, UpdateResultReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
        }
    }

    private fun fetchLatest(): Release {
        val conn = open(LATEST_RELEASE_API)
        val body = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            throw NoReleases() // GitHub answers 404 when the repo has no releases
        }
        val json = JSONObject(body)
        val assets = json.optJSONArray("assets")
        var apkUrl: String? = null
        var apkSize = -1L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url")
                    apkSize = a.optLong("size", -1)
                    break
                }
            }
        }
        // Find the version in the tag ("v2.1"), else the release title ("ThorCorners-2.1"),
        // else the APK file name ("ThorCorners-2.1.apk").
        val apkName = apkUrl?.substringAfterLast('/')
        val version = listOf(json.optString("tag_name"), json.optString("name"), apkName.orEmpty())
            .firstNotNullOfOrNull(::extractVersion)
            ?: throw UnknownVersion(json.optString("name").ifBlank { json.optString("tag_name") })
        return Release(
            version = version,
            notes = json.optString("body"),
            apkUrl = apkUrl,
            apkSize = apkSize,
        )
    }

    private class UnknownVersion(val releaseName: String) : Exception()

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true // release downloads redirect to GitHub's file host
            setRequestProperty("User-Agent", "ThorCorners/${installedVersion()}")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    private fun installedVersion(): String =
        runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull() ?: "0"

    private fun message(title: String, text: String, offerPage: Boolean = false) {
        AlertDialog.Builder(activity).setTitle(title).setMessage(text).apply {
            setPositiveButton("OK", null)
            if (offerPage) setNeutralButton("Open GitHub") { _, _ ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE))) }
            }
        }.show()
    }

    private class Progress(val dialog: AlertDialog, private val bar: ProgressBar, private val label: TextView) {
        fun set(done: Long, total: Long) {
            if (total > 0) {
                bar.progress = (done * 100 / total).toInt()
                label.text = "%.1f / %.1f MB".format(done / 1e6, total / 1e6)
            } else {
                label.text = "%.1f MB".format(done / 1e6)
            }
        }
    }

    private fun progressDialog(title: String, indeterminate: Boolean): Progress {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = indeterminate
            max = 100
        }
        val label = TextView(activity)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(bar)
            addView(label)
        }
        val dialog = AlertDialog.Builder(activity).setTitle(title).setView(box).setCancelable(false).show()
        return Progress(dialog, bar, label)
    }

    companion object {
        private const val REPO = "crowdoeswobs/ThorCorners"
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"
        const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

        private val VERSION = Regex("""\d+(?:\.\d+)*""")

        /** "v2.1" -> "2.1", "ThorCorners-0.1.apk" -> "0.1", "Release" -> null. */
        fun extractVersion(text: String): String? = VERSION.find(text)?.value

        /** Compares "2.1" with "2.0.3" etc. Positive if [a] is newer than [b]. */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.split('.', '-', ' ').map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val pa = parts(a)
            val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
                if (d != 0) return d
            }
            return 0
        }
    }
}
