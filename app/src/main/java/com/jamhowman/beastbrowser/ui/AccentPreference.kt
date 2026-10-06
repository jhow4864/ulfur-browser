package com.jamhowman.beastbrowser.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.data.Accent

/** Row of neon swatches (GX-style accent picker). */
class AccentPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : Preference(context, attrs) {

    init { layoutResource = R.layout.pref_accent }

    override fun onGetDefaultValue(a: android.content.res.TypedArray, index: Int): Any? = a.getString(index)

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.isClickable = false
        val current = Accent.from(getPersistedString(Accent.RED.key))
        (holder.findViewById(R.id.accentName) as TextView).text = current.label
        val row = holder.findViewById(R.id.swatches) as LinearLayout
        row.removeAllViews()
        val size = dp(context, 40)
        Accent.entries.forEach { accent ->
            val frame = FrameLayout(context)
            val ring = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x00000000)
                    if (accent == current) setStroke(dp(context, 2), accent.color)
                }
            }
            val dot = ImageView(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent.color) }
                if (accent == current) { setImageResource(R.drawable.ic_check); imageTintList = android.content.res.ColorStateList.valueOf(accent.onColor) }
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = accent.label
            }
            frame.addView(ring, FrameLayout.LayoutParams(size + dp(context, 10), size + dp(context, 10)))
            frame.addView(dot, FrameLayout.LayoutParams(size, size).apply { leftMargin = dp(context, 5); topMargin = dp(context, 5) })
            frame.setOnClickListener {
                if (callChangeListener(accent.key)) { persistString(accent.key); notifyChanged() }
            }
            row.addView(frame, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }
}
