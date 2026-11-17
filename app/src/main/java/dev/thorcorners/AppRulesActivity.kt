package dev.thorcorners

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Pick apps that should hide the corners while they're open, on the top screen,
 * the bottom screen, or both. Apps with a rule are listed first.
 */
class AppRulesActivity : Activity() {

    private data class App(val pkg: String, val label: String, val icon: Drawable)

    private var allApps: List<App> = emptyList()
    private var shown: List<App> = emptyList()
    private var query = ""
    private lateinit var permissionBox: LinearLayout
    private lateinit var status: TextView
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), 0)
        }
        root.addView(text("Per-app rules", 26f))
        root.addView(text("While an app is open, hide the corners on the screens you choose.", 15f))

        permissionBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        permissionBox.addView(text("Needs \"Usage access\" to see which app is open.", 15f))
        permissionBox.addView(Button(this).apply {
            text = "Grant usage access"
            setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        })
        root.addView(permissionBox)

        root.addView(EditText(this).apply {
            hint = "Search apps"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s.toString(); filter() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
        status = text("Loading apps…", 15f)
        root.addView(status)

        root.addView(ListView(this).apply {
            adapter = this@AppRulesActivity.adapter
            setOnItemClickListener { _, _, position, _ -> pickRule(shown[position]) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        loadApps()
    }

    override fun onResume() {
        super.onResume()
        permissionBox.visibility =
            if (ForegroundAppWatcher.hasPermission(this)) View.GONE else View.VISIBLE
    }

    private fun loadApps() = Thread {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != packageName }
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
        runOnUiThread {
            allApps = apps
            status.visibility = View.GONE
            filter()
        }
    }.start()

    /** Apps with a rule first, then alphabetical; narrowed by the search box. */
    private fun filter() {
        shown = allApps
            .filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
            .sortedWith(
                compareBy<App> { if (Prefs.appRule(this, it.pkg) != Prefs.RULE_NONE) 0 else 1 }
                    .thenBy { it.label.lowercase() }
            )
        adapter.notifyDataSetChanged()
    }

    private fun pickRule(app: App) {
        val values = intArrayOf(Prefs.RULE_NONE, Prefs.RULE_HIDE_TOP, Prefs.RULE_HIDE_BOTTOM, Prefs.RULE_HIDE_BOTH)
        val current = values.indexOf(Prefs.appRule(this, app.pkg)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setSingleChoiceItems(values.map { ruleName(it) }.toTypedArray(), current) { dialog, which ->
                Prefs.setAppRule(this, app.pkg, values[which])
                dialog.dismiss()
                filter()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun ruleName(rule: Int) = when (rule) {
        Prefs.RULE_HIDE_TOP -> "Hide corners on top screen"
        Prefs.RULE_HIDE_BOTTOM -> "Hide corners on bottom screen"
        Prefs.RULE_HIDE_BOTH -> "Hide corners on both screens"
        else -> "Keep corners (no rule)"
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@AppRulesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                addView(ImageView(context), LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), 0, 0, 0)
                    addView(text("", 16f).apply { setPadding(0, 0, 0, 0) })
                    addView(text("", 13f).apply { setPadding(0, 0, 0, 0); alpha = 0.7f })
                })
            }
            val app = shown[position]
            val rule = Prefs.appRule(this@AppRulesActivity, app.pkg)
            (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            val texts = row.getChildAt(1) as LinearLayout
            (texts.getChildAt(0) as TextView).text = app.label
            (texts.getChildAt(1) as TextView).text =
                if (rule == Prefs.RULE_NONE) app.pkg else ruleName(rule)
            return row
        }
    }

    private fun text(s: String, sp: Float) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
