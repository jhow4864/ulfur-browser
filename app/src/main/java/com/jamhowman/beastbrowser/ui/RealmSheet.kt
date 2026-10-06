package com.jamhowman.beastbrowser.ui

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.Realm
import java.util.Locale

/** "Realms" bottom sheet (2.3.8): three seals (Work · Play · Ghost) plus a wipe/burn action. Built in code. */
object RealmSheet {

    /**
     * @param tabCounts open (or saved, for realms not restored yet) tabs per realm
     * @param onPick called with the chosen realm when it differs from [current]
     * @param onWipe "Wipe Work cookies & site data" / "Burn Ghost" for the current realm
     */
    fun show(
        activity: AppCompatActivity,
        current: Realm,
        tabCounts: Map<Realm, Int>,
        onPick: (Realm) -> Unit,
        onWipe: (Realm) -> Unit,
    ) {
        val dialog = BottomSheetDialog(activity)
        val pad = dp(activity, 24)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(activity, 12), pad, pad)
        }
        root.addView(View(activity).apply { setBackgroundResource(R.drawable.bg_sheet_handle) },
            LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(activity, 16)
            })
        root.addView(TextView(activity).apply {
            text = activity.getString(R.string.realms)
            setTextColor(activity.getColor(R.color.text_primary))
            textSize = 18f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            letterSpacing = 0.1f
        })
        root.addView(TextView(activity).apply {
            text = "Each realm has its own cookie jar, site storage and tab list."
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 13f
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(activity, 4)
            bottomMargin = dp(activity, 20)
        })

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        Realm.entries.forEach { realm ->
            row.addView(seal(activity, realm, realm == current, tabCounts[realm] ?: 0) { sealView ->
                sealView.animate().scaleX(1.12f).scaleY(1.12f).setDuration(110).withEndAction {
                    dialog.dismiss()
                    if (realm != current) onPick(realm)
                }.start()
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row)

        root.addView(TextView(activity).apply {
            text = when (current) {
                Realm.PLAY -> activity.getString(R.string.realm_note_play)
                Realm.WORK -> "Work has its own persistent jar. Shared across realms: uBlock filters, bookmarks, history, saved passwords, downloads."
                Realm.GHOST -> "Ghost is private browsing in its own jar: no history, nothing on disk, wiped when the last Ghost tab closes."
            }
            setTextColor(activity.getColor(R.color.text_hint))
            textSize = 12f
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(activity, 20)
        })

        if (current != Realm.PLAY) {
            val accent = Prefs.accentFor(current)
            root.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = if (current == Realm.GHOST) "Burn Ghost (close tabs + wipe)" else "Wipe Work cookies & site data"
                setTextColor(accent.color)
                strokeColor = ColorStateList.valueOf(accent.withAlpha(0x88))
                setOnClickListener { dialog.dismiss(); onWipe(current) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 12)
            })
        }

        dialog.setContentView(root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    /** Ringed seal with the realm icon, its name and a tab count; the current realm is filled with its accent. */
    private fun seal(activity: AppCompatActivity, realm: Realm, isCurrent: Boolean, tabs: Int, onClick: (View) -> Unit): View {
        val accent = Prefs.accentFor(realm)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            contentDescription = "${realm.label} realm" + if (isCurrent) " (current)" else ""
        }
        val ringSize = dp(activity, 72)
        val frame = FrameLayout(activity)
        frame.addView(View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isCurrent) accent.withAlpha(0x22) else 0)
                setStroke(dp(activity, if (isCurrent) 2 else 1), if (isCurrent) accent.color else accent.withAlpha(0x66))
            }
        }, FrameLayout.LayoutParams(ringSize, ringSize))
        val core = dp(activity, 52)
        frame.addView(ImageView(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (isCurrent) setColor(accent.color)
                else {
                    setColor(activity.getColor(R.color.surface2))
                    setStroke(dp(activity, 1), accent.withAlpha(0x99))
                }
            }
            setImageResource(realm.sealIcon)
            imageTintList = ColorStateList.valueOf(if (isCurrent) accent.onColor else accent.color)
            scaleType = ImageView.ScaleType.CENTER
        }, FrameLayout.LayoutParams(core, core, Gravity.CENTER))
        col.addView(frame, LinearLayout.LayoutParams(ringSize, ringSize))
        col.addView(TextView(activity).apply {
            text = realm.label.uppercase(Locale.ROOT)
            letterSpacing = 0.2f
            textSize = 12f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setTextColor(if (isCurrent) accent.color else activity.getColor(R.color.text_primary))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(activity, 8)
        })
        col.addView(TextView(activity).apply {
            text = when {
                realm == Realm.GHOST && tabs == 0 -> "private"
                tabs == 1 -> "1 tab"
                tabs > 0 -> "$tabs tabs"
                else -> "saved"
            }
            textSize = 11f
            setTextColor(activity.getColor(R.color.text_hint))
            gravity = Gravity.CENTER
        })
        col.setOnClickListener { onClick(frame) }
        return col
    }
}
