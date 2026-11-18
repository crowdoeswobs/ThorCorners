package dev.thorcorners

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast

/** Gets the result of an update install from Android's installer. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            // Android wants the user to confirm: show its "Update this app?" screen.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app restarts as the new version
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // user tapped Cancel
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("ThorCorners", "Update install failed: $status $msg")
                val hint = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT)
                    "It's signed differently from the installed app." else msg ?: "Unknown error."
                Toast.makeText(context, "Update failed: $hint", Toast.LENGTH_LONG).show()
            }
        }
    }
}
