package com.jamhowman.beastbrowser.filters

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 2.10 Ulfur #3: the user's extra filter lists and element-picker rules. Metadata in `filters.json`, each list's
 * sanitised rules in `filter-lists/<id>.txt`. [compile] builds the block that the uBO bridge keeps inside a marked
 * region of uBO's "My filters". Every mutation persists immediately; the caller then pushes [compile] to uBO.
 */
class FilterStore(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {

    data class FilterList(
        val id: String, val url: String, val title: String, val enabled: Boolean = true,
        val updatedAt: Long = 0, val ruleCount: Int = 0, val dropped: Int = 0, val error: String? = null,
    )

    private val meta = File(dir, "filters.json")
    private val listDir = File(dir, "filter-lists")
    var lists: List<FilterList> = emptyList(); private set
    var pickerRules: List<FilterRules.PickerRule> = emptyList(); private set

    init { load() }

    private fun load() {
        runCatching {
            if (!meta.isFile) return
            val o = JSONObject(meta.readText())
            lists = o.optJSONArray("lists")?.let { a -> (0 until a.length()).mapNotNull { i ->
                val j = a.optJSONObject(i) ?: return@mapNotNull null
                val url = j.optString("url")
                if (FilterRules.checkListUrl(url) != null) return@mapNotNull null
                FilterList(j.optString("id"), url, j.optString("title"), j.optBoolean("enabled", true),
                    j.optLong("updatedAt"), j.optInt("ruleCount"), j.optInt("dropped"), j.optString("error").takeIf { it.isNotEmpty() })
            } }.orEmpty().filter { it.id.matches(ID_RE) }
            pickerRules = o.optJSONArray("picker")?.let { a -> (0 until a.length()).mapNotNull { i ->
                val j = a.optJSONObject(i) ?: return@mapNotNull null
                FilterRules.pickerRule(j.optString("host"), j.optString("selector"), j.optLong("createdAt"))
            } }.orEmpty()
        } // an unreadable file starts empty rather than crashing
    }

    private fun save() {
        dir.mkdirs()
        val o = JSONObject()
            .put("lists", JSONArray().apply { lists.forEach { l -> put(JSONObject().put("id", l.id).put("url", l.url)
                .put("title", l.title).put("enabled", l.enabled).put("updatedAt", l.updatedAt).put("ruleCount", l.ruleCount)
                .put("dropped", l.dropped).put("error", l.error ?: "")) } })
            .put("picker", JSONArray().apply { pickerRules.forEach { r -> put(JSONObject().put("host", r.host)
                .put("selector", r.selector).put("createdAt", r.createdAt)) } })
        val tmp = File(dir, "filters.json.tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(meta)) { meta.writeText(o.toString()); tmp.delete() }
    }

    // ------------------------------------------------------------------ lists

    sealed class AddResult {
        data class Added(val list: FilterList) : AddResult()
        data class Rejected(val reason: String) : AddResult()
    }

    fun add(url: String, title: String? = null): AddResult {
        val u = url.trim()
        FilterRules.checkListUrl(u)?.let { return AddResult.Rejected(it) }
        if (lists.any { it.url == u }) return AddResult.Rejected("That list is already added")
        if (lists.size >= FilterRules.MAX_LISTS) return AddResult.Rejected("You can add up to ${FilterRules.MAX_LISTS} lists")
        val id = "l" + now().toString(36) + (lists.size + 1)
        val l = FilterList(id, u, title?.trim()?.takeIf { it.isNotEmpty() }?.take(80) ?: u.substringAfterLast('/').ifEmpty { u }.take(80))
        lists = lists + l
        save()
        return AddResult.Added(l)
    }

    fun remove(id: String) {
        lists = lists.filterNot { it.id == id }
        File(listDir, "$id.txt").delete()
        save()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        lists = lists.map { if (it.id == id) it.copy(enabled = enabled) else it }
        save()
    }

    /**
     * Fetches and stores one list with [fetch] (FilterFetcher.fetch in the app). On any failure the previous rules
     * are kept and the error is recorded. Returns the updated entry, or null if [id] is unknown.
     */
    fun update(id: String, fetch: (String) -> String): FilterList? {
        val l = lists.firstOrNull { it.id == id } ?: return null
        val updated = try {
            FilterRules.checkListUrl(l.url)?.let { throw java.io.IOException(it) }
            val s = FilterRules.sanitize(fetch(l.url))
            listDir.mkdirs()
            File(listDir, "$id.txt").writeText(s.rules.joinToString("\n"))
            l.copy(updatedAt = now(), ruleCount = s.rules.size, dropped = s.dropped,
                error = if (s.truncated) "Only the first ${FilterRules.MAX_RULES_PER_LIST} rules are used" else null)
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            l.copy(error = (e.message ?: e.javaClass.simpleName).take(120))
        }
        lists = lists.map { if (it.id == id) updated else it }
        save()
        return updated
    }

    /**
     * Designer nudge (2.10): age in whole days of the oldest enabled, downloaded list when it's more than
     * [STALE_DAYS] days old; null otherwise (nothing to nudge about). Lists never downloaded don't count.
     */
    fun staleDays(nowMs: Long = now()): Int? {
        val oldest = lists.filter { it.enabled && it.updatedAt > 0 }.minOfOrNull { it.updatedAt } ?: return null
        val age = nowMs - oldest
        if (age <= STALE_DAYS * DAY_MS) return null
        return (age / DAY_MS).toInt()
    }

    fun rulesOf(id: String): List<String> = runCatching {
        File(listDir, "$id.txt").takeIf { it.isFile }?.readLines().orEmpty()
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ picker rules

    /** Adds a picker rule made on [pageHost]; null when host or selector is unsafe. */
    fun addPickerRule(pageHost: String?, selector: String?): FilterRules.PickerRule? {
        val r = FilterRules.pickerRule(pageHost, selector, now()) ?: return null
        if (pickerRules.none { it.host == r.host && it.selector == r.selector }) { pickerRules = pickerRules + r; save() }
        return r
    }

    fun removePickerRule(rule: FilterRules.PickerRule) {
        pickerRules = pickerRules.filterNot { it.host == rule.host && it.selector == rule.selector }
        save()
    }

    fun pickerRulesFor(pageHost: String?) = pickerRules.filter { FilterRules.appliesTo(it, pageHost) }

    /** The text uBO gets: enabled lists' rules, then picker rules (each re-validated). */
    fun compile(): String = buildString {
        lists.filter { it.enabled }.forEach { l ->
            append("! list: ").append(l.title.replace(Regex("[\\r\\n]"), " ")).append('\n')
            rulesOf(l.id).forEach { append(it).append('\n') } // sanitised when fetched
        }
        pickerRules.forEach { r -> FilterRules.pickerRule(r.host, r.selector)?.let { append(it.filter).append('\n') } }
    }.trimEnd()

    companion object {
        const val STALE_DAYS = 7L
        const val DAY_MS = 24L * 60 * 60 * 1000
        private val ID_RE = Regex("[a-z0-9]{1,24}")
    }
}
