package com.jamhowman.beastbrowser.ui

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.filters.FilterAutoUpdate
import com.jamhowman.beastbrowser.filters.FilterRules
import com.jamhowman.beastbrowser.filters.FilterStore
import com.jamhowman.beastbrowser.filters.Filters
import java.text.DateFormat
import java.util.Date

/**
 * 2.10 Ulfur #3: the user's own filter lists (add by https address, enable/disable, update, remove) and the
 * element-picker rules, grouped by the site they were made on, each deletable. uBO's stock lists stay in its dashboard.
 */
class FiltersActivity : AppCompatActivity() {
    private lateinit var body: LinearLayout
    private val store get() = Filters.store(this)
    private val onChange: () -> Unit = { render() }
    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        title = getString(R.string.filters_title)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val scroll = ScrollView(this).apply { addView(body); setBackgroundColor(getColor(R.color.bg)) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(s.left, s.top, s.right, s.bottom); insets
        }
        Filters.addListener(onChange)
        render()
    }

    override fun onDestroy() { Filters.removeListener(onChange); super.onDestroy() }

    private fun text(t: CharSequence, size: Float = 15f, secondary: Boolean = false) = TextView(this).apply {
        text = t; textSize = size
        setTextColor(getColor(if (secondary) R.color.text_secondary else R.color.text_primary))
    }

    private fun header(t: String) = text(t, 18f).apply { setPadding(0, pad, 0, pad / 2) }

    private fun button(t: String, outlined: Boolean = true, onClick: () -> Unit) =
        MaterialButton(this, null, if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle
            else com.google.android.material.R.attr.materialButtonStyle).apply { text = t; setOnClickListener { onClick() } }

    private fun render() {
        body.removeAllViews()
        // Designer nudge: "Lists are N days old · Update" at the top when an enabled list is over 7 days old.
        store.staleDays()?.let { days ->
            body.addView(text(resources.getQuantityString(R.plurals.filters_stale, days, days), 13f).apply {
                setTextColor(getColor(R.color.warn))
                setPadding(0, 0, 0, pad / 2)
                isClickable = true; isFocusable = true
                setOnClickListener { updateAll() }
            })
        }
        body.addView(header(getString(R.string.filters_lists_header)))
        body.addView(text(getString(R.string.filters_lists_note), 13f, secondary = true))
        if (store.lists.isNotEmpty()) body.addView(button(getString(R.string.filters_update_all)) { updateAll() })
        autoUpdateRows()
        store.lists.forEach { l -> body.addView(listRow(l)) }
        // Designer spec: "Add list by URL" at the bottom of the lists; https only (FilterRules.checkListUrl).
        val input = EditText(this).apply {
            hint = "https://…/list.txt"; inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        body.addView(input)
        body.addView(LinearLayout(this).apply {
            addView(button(getString(R.string.filters_add), outlined = false) {
                when (val r = store.add(input.text.toString())) {
                    is FilterStore.AddResult.Rejected -> Toast.makeText(this@FiltersActivity, r.reason, Toast.LENGTH_LONG).show()
                    is FilterStore.AddResult.Added -> {
                        Toast.makeText(this@FiltersActivity, R.string.filters_updating, Toast.LENGTH_SHORT).show()
                        Filters.update(this@FiltersActivity, listOf(r.list.id))
                        render()
                    }
                }
            })
        })

        body.addView(header(getString(R.string.filters_picker_header)))
        val rules = store.pickerRules
        if (rules.isEmpty()) body.addView(text(getString(R.string.filters_picker_empty), 13f, secondary = true))
        rules.groupBy { it.host }.toSortedMap().forEach { (host, list) ->
            body.addView(text(host, 15f).apply { setPadding(0, pad / 2, 0, 0) })
            list.forEach { r ->
                body.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(r.selector, 13f, secondary = true), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(button(getString(R.string.filters_delete)) {
                        store.removePickerRule(r); Filters.changedLocally(this@FiltersActivity)
                    })
                })
            }
        }
    }

    /** 2.11: weekly auto-update (on by default, Wi-Fi only unless mobile data is allowed) and its last run. */
    private fun autoUpdateRows() {
        val sp = Prefs.sp
        fun toggle(label: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) =
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(getString(label), 15f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(MaterialSwitch(this@FiltersActivity).apply {
                    isChecked = checked; isEnabled = enabled; contentDescription = getString(label)
                    setOnCheckedChangeListener { _, on -> onChange(on) }
                })
            }
        val auto = FilterAutoUpdate.enabled(sp)
        body.addView(toggle(R.string.filters_auto, auto, true) { on ->
            sp.edit().putBoolean(FilterAutoUpdate.KEY_ENABLED, on).apply(); FilterAutoUpdate.schedule(this); render()
        })
        body.addView(text(getString(R.string.filters_auto_summary), 12f, secondary = true))
        body.addView(toggle(R.string.filters_auto_mobile, FilterAutoUpdate.allowMobile(sp), auto) { on ->
            sp.edit().putBoolean(FilterAutoUpdate.KEY_MOBILE, on).apply(); FilterAutoUpdate.schedule(this)
        })
        val last = sp.getLong(FilterAutoUpdate.KEY_LAST, 0L)
        val err = sp.getString(FilterAutoUpdate.KEY_LAST_ERROR, null)
        val whenText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))
        body.addView(text(when {
            last <= 0L -> getString(R.string.filters_auto_never)
            err != null -> getString(R.string.filters_auto_last_failed, whenText, err)
            else -> getString(R.string.filters_auto_last, whenText)
        }, 12f, secondary = true).apply { setPadding(0, 0, 0, pad / 2) })
    }

    private fun updateAll() {
        Toast.makeText(this, R.string.filters_updating, Toast.LENGTH_SHORT).show()
        Filters.update(this, store.lists.map { it.id })
    }

    private fun listRow(l: FilterStore.FilterList): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, pad / 2, 0, pad / 2)
        addView(LinearLayout(this@FiltersActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(l.title, 16f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(MaterialSwitch(this@FiltersActivity).apply {
                isChecked = l.enabled
                contentDescription = getString(R.string.filters_enable, l.title)
                setOnCheckedChangeListener { _, on -> store.setEnabled(l.id, on); Filters.changedLocally(this@FiltersActivity) }
            })
        })
        addView(text(l.url, 12f, secondary = true))
        val status = buildString {
            if (l.updatedAt > 0) append(getString(R.string.filters_status, l.ruleCount,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(l.updatedAt))))
            else append(getString(R.string.filters_never))
            if (l.dropped > 0) append(" · ").append(getString(R.string.filters_dropped, l.dropped))
            l.error?.let { append(" · ").append(it) }
        }
        addView(text(status, 12f, secondary = true))
        addView(LinearLayout(this@FiltersActivity).apply {
            addView(button(getString(R.string.filters_update)) {
                Toast.makeText(this@FiltersActivity, R.string.filters_updating, Toast.LENGTH_SHORT).show()
                Filters.update(this@FiltersActivity, listOf(l.id))
            })
            addView(button(getString(R.string.filters_remove)) {
                MaterialAlertDialogBuilder(this@FiltersActivity).setTitle(getString(R.string.filters_remove_q, l.title))
                    .setPositiveButton(R.string.filters_remove) { _, _ -> store.remove(l.id); Filters.changedLocally(this@FiltersActivity) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }.apply { (layoutParams as? LinearLayout.LayoutParams)?.marginStart = pad / 2 })
        })
    }

    companion object { val MAX = FilterRules.MAX_LISTS }
}
