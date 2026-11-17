package dev.thorcorners

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * Settings screen, built in code so there are no layout files to keep in sync.
 * Every control writes straight to Prefs; the running service picks changes up live.
 */
class MainActivity : Activity() {

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        build() // rebuild so permission state, display list and rule count are always current
    }

    private fun build() {
        root.removeAllViews()
        root.addView(text("Thor Corners", 26f))

        if (!CornerService.isEnabled(this)) {
            root.addView(text(
                "One-time setup: turn on Thor Corners in Accessibility settings.\n\n" +
                    "Why accessibility? It's the only way to draw corners that are true black, " +
                    "sit above the status bar, and never block your touches. " +
                    "Thor Corners doesn't read anything on your screen.",
                15f,
            ))
            root.addView(Button(this).apply {
                text = "Open Accessibility settings"
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            })
            return
        }

        addPowerButton()
        addSeparator()

        // Top screen first, then the bottom screen.
        val displays = getSystemService(DisplayManager::class.java).displays
            .sortedBy { if (Prefs.isTopScreen(it.displayId)) 0 else 1 }
        for (display in displays) addDisplaySection(display)

        addGeneralSection()
        addResetButton()
    }

    /**
     * The master on/off control: a big button that looks pressed in and lit green while the
     * corners are on, and raised and grey while they're off.
     */
    private fun addPowerButton() {
        val fill = GradientDrawable().apply { cornerRadius = dp(18).toFloat() }
        val button = Button(this).apply {
            background = fill
            stateListAnimator = null // we handle the raised/pressed look ourselves
            isAllCaps = false
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        var focused = false

        fun render(on: Boolean, animate: Boolean) {
            button.text = SpannableString(
                if (on) "●  Corners ON\nTap to turn off" else "○  Corners OFF\nTap to turn on"
            ).apply {
                val split = indexOf('\n')
                setSpan(StyleSpan(Typeface.BOLD), 0, split, 0)
                setSpan(AbsoluteSizeSpan(22, true), 0, split, 0)
                setSpan(AbsoluteSizeSpan(13, true), split + 1, length, 0)
            }
            fill.setColor(Color.parseColor(if (on) "#2E7D32" else "#2A2A2A"))
            // Pressed in: darker inset edge. Raised: subtle outline. Gamepad focus: white outline.
            fill.setStroke(
                dp(if (focused) 3 else 2),
                Color.parseColor(if (focused) "#FFFFFF" else if (on) "#1B5E20" else "#555555"),
            )
            val scale = if (on) 0.97f else 1f
            val elevation = dp(if (on) 0 else 8).toFloat()
            if (animate) {
                button.animate().scaleX(scale).scaleY(scale).translationZ(elevation).setDuration(120).start()
            } else {
                button.scaleX = scale; button.scaleY = scale; button.translationZ = elevation
            }
        }

        button.setOnClickListener {
            val on = !Prefs.isRunning(this)
            Prefs.setRunning(this, on)
            render(on, animate = true)
        }
        button.setOnFocusChangeListener { _, hasFocus ->
            focused = hasFocus
            render(Prefs.isRunning(this), animate = false)
        }
        render(Prefs.isRunning(this), animate = false)

        root.addView(button, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(84)
        ).apply { topMargin = dp(12) })
    }

    /** A switch that's clearly green when on and grey when off, matching the power button. */
    private fun themedSwitch() = Switch(this).apply {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Color.parseColor("#4CAF50"), Color.parseColor("#9E9E9E")))
        trackTintList = ColorStateList(states, intArrayOf(Color.parseColor("#2E7D32"), Color.parseColor("#424242")))
    }

    private fun addSeparator() {
        root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#33FFFFFF")) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(24)
                bottomMargin = dp(4)
            })
    }

    private fun addDisplaySection(display: Display) {
        val id = display.displayId
        val label = if (Prefs.isTopScreen(id)) "Top screen" else "Bottom screen"
        root.addView(header("$label  ·  ${display.name} (id $id)"))

        root.addView(themedSwitch().apply {
            text = "Rounded corners"
            isChecked = Prefs.isDisplayEnabled(this@MainActivity, id)
            setOnCheckedChangeListener { _, on -> Prefs.setDisplayEnabled(this@MainActivity, id, on) }
        })

        addSlider(
            label = { "Radius: $it dp" },
            min = 0, max = Prefs.MAX_RADIUS_DP,
            value = Prefs.radiusDp(this, id),
            onChange = { Prefs.setRadiusDp(this, id, it) },
        )
    }

    private fun addGeneralSection() {
        root.addView(header("General"))

        root.addView(themedSwitch().apply {
            text = "Start on boot"
            isChecked = Prefs.startOnBoot(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setStartOnBoot(this@MainActivity, on) }
        })

        // Low battery: a switch, plus a level slider that only shows while the switch is on.
        val levelBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(themedSwitch().apply {
            text = "Turn off on low battery"
            isChecked = Prefs.lowBatteryEnabled(this@MainActivity)
            levelBox.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, on ->
                Prefs.setLowBatteryEnabled(this@MainActivity, on)
                levelBox.visibility = if (on) View.VISIBLE else View.GONE
            }
        })
        root.addView(levelBox)
        addSlider(
            label = { "Turn off at $it% or lower (back on when charging)" },
            min = Prefs.MIN_LOW_BATTERY_LEVEL, max = Prefs.MAX_LOW_BATTERY_LEVEL,
            value = Prefs.lowBatteryLevel(this),
            onChange = { Prefs.setLowBatteryLevel(this, it) },
            into = levelBox,
        )

        val ruleCount = Prefs.allAppRules(this).size
        root.addView(Button(this).apply {
            text = if (ruleCount == 0) "Per-app rules" else "Per-app rules ($ruleCount)"
            setOnClickListener { startActivity(Intent(this@MainActivity, AppRulesActivity::class.java)) }
        })
    }

    private fun addResetButton() {
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(24)))
        root.addView(Button(this).apply {
            text = "Reset to defaults"
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F"))
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Reset to defaults?")
                    .setMessage("Radius, screen toggles, start on boot, low battery and all per-app rules go back to their defaults.")
                    .setPositiveButton("Reset") { _, _ ->
                        Prefs.resetToDefaults(this@MainActivity)
                        build()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
    }

    /** A labelled slider. SeekBar has no minimum before API 26 styling quirks, so we offset by [min]. */
    private fun addSlider(
        label: (Int) -> String,
        min: Int,
        max: Int,
        value: Int,
        onChange: (Int) -> Unit,
        into: LinearLayout = root,
    ) {
        val text = text(label(value), 15f)
        into.addView(text)
        into.addView(SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    text.text = label(progress + min)
                    if (fromUser) onChange(progress + min)
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        })
    }

    private fun header(s: String) = text(s, 18f).apply { setPadding(0, dp(24), 0, dp(4)) }

    private fun text(s: String, sp: Float) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
