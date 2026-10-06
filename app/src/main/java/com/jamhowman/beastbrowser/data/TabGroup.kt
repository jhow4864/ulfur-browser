package com.jamhowman.beastbrowser.data

import androidx.annotation.StringRes
import com.jamhowman.beastbrowser.R

/** Tab groups (2.3.5): same ids, labels and accents as [BookmarkFolder]. Saved per tab in `saved_tab_groups`. */
enum class TabGroup(val id: String, @StringRes val labelRes: Int, val accent: Accent) {
    WORK("work", R.string.folder_work, Accent.RED),
    PERSONAL("personal", R.string.folder_personal, Accent.PURPLE),
    SHOPPING("shopping", R.string.folder_shopping, Accent.CYAN),
    READING("reading", R.string.folder_reading, Accent.GREEN),
    OTHER("other", R.string.folder_other, Accent.ORANGE);

    companion object {
        fun from(id: String?): TabGroup? = entries.firstOrNull { it.id == id }
    }
}
