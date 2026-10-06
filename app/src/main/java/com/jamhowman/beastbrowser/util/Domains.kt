package com.jamhowman.beastbrowser.util

/**
 * Light-weight "registrable domain" (eTLD+1) approximation used for first-party checks and per-site
 * shield settings. Not a full Public Suffix List, but covers the common multi-part suffixes.
 */
object Domains {
    private val multiPartSuffixes = hashSetOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "ltd.uk", "plc.uk", "me.uk", "net.uk", "nhs.uk", "police.uk", "sch.uk",
        "com.au", "net.au", "org.au", "edu.au", "gov.au", "co.nz", "org.nz", "net.nz", "govt.nz",
        "co.jp", "ne.jp", "or.jp", "ac.jp", "co.kr", "or.kr", "com.br", "net.br", "com.cn", "net.cn", "org.cn",
        "com.mx", "co.in", "net.in", "org.in", "co.za", "org.za", "com.tr", "com.sg", "com.hk", "com.tw",
        "co.id", "com.ar", "com.my", "com.ph", "com.pk", "com.ng", "com.eg", "com.sa", "co.il", "com.ua",
        "github.io", "gitlab.io", "blogspot.com", "herokuapp.com", "appspot.com", "netlify.app", "vercel.app",
        "pages.dev", "workers.dev", "web.app", "firebaseapp.com", "azurewebsites.net", "cloudfront.net",
        "s3.amazonaws.com", "wordpress.com", "tumblr.com",
    )

    fun registrable(host: String?): String {
        if (host.isNullOrEmpty()) return ""
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty() || h[0] == '[' || h.all { it.isDigit() || it == '.' }) return h
        val labels = h.split('.')
        if (labels.size <= 2) return h
        val last2 = labels.takeLast(2).joinToString(".")
        if (last2 in multiPartSuffixes) return labels.takeLast(3).joinToString(".")
        val last3 = labels.takeLast(3).joinToString(".")
        if (labels.size >= 4 && last3 in multiPartSuffixes) return labels.takeLast(4).joinToString(".")
        return last2
    }

    fun sameSite(a: String?, b: String?): Boolean {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return false
        return registrable(a) == registrable(b)
    }

    /** Host for display: lower-case without a leading "www." */
    fun display(host: String?): String = host.orEmpty().lowercase().removePrefix("www.")
}
