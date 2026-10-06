package com.jamhowman.beastbrowser.data

import androidx.annotation.StringRes
import com.jamhowman.beastbrowser.R

/** Fixed bookmark folders (2.3.4). [id] is what's stored in `bookmarks.folder_id`. */
enum class BookmarkFolder(val id: String, @StringRes val labelRes: Int, val accent: Accent) {
    WORK("work", R.string.folder_work, Accent.RED),
    PERSONAL("personal", R.string.folder_personal, Accent.PURPLE),
    SHOPPING("shopping", R.string.folder_shopping, Accent.CYAN),
    READING("reading", R.string.folder_reading, Accent.GREEN),
    OTHER("other", R.string.folder_other, Accent.ORANGE);

    companion object {
        fun from(id: String?): BookmarkFolder? = entries.firstOrNull { it.id == id }
    }
}
