package com.jamhowman.beastbrowser.data

import org.mozilla.geckoview.GeckoRuntimeSettings
import java.net.URI
import java.util.Locale

/**
 * 2.5: Secure DNS (DNS over HTTPS) through Gecko's Trusted Recursive Resolver
 * (GeckoRuntimeSettings.setTrustedRecursiveResolverMode / setTrustedRecursiveResolverUri).
 *
 * Pref keys: `doh_mode` ([Mode.key]), `doh_provider` ([Provider.key]), `doh_nextdns_id`, `doh_custom_url`.
 * Off is a mode, not a provider, so the provider choice survives switching off and on again.
 */
object SecureDns {
    /** Default for installs that never chose: Off (TRR disabled). A product call for James; change this one line. */
    val DEFAULT_MODE = Mode.OFF
    /** Provider pre-selected when the user first turns Secure DNS on. */
    val DEFAULT_PROVIDER = Provider.CLOUDFLARE

    enum class Mode(val key: String) {
        /** TRR_MODE_DISABLED (5), not TRR_MODE_OFF (0): mode 0 lets Gecko's DoH rollout switch DoH on by itself. */
        OFF("off"),
        /** "Automatic": DoH, falling back to the system resolver if it fails (TRR_MODE_FIRST). */
        DEFAULT("default"),
        /** "Strict": DoH only, no fallback; sites fail to load if the resolver is unreachable (TRR_MODE_ONLY). */
        MAX("max");

        companion object {
            fun from(key: String?): Mode = entries.firstOrNull { it.key == key } ?: DEFAULT_MODE
        }
    }

    enum class Provider(val key: String, val uri: String?) {
        CLOUDFLARE("cloudflare", "https://cloudflare-dns.com/dns-query"),
        QUAD9("quad9", "https://dns.quad9.net/dns-query"),
        /** https://dns.nextdns.io/<config ID> from `doh_nextdns_id`. */
        NEXTDNS("nextdns", null),
        ADGUARD("adguard", "https://dns.adguard-dns.com/dns-query"),
        /** Any https DoH endpoint from `doh_custom_url`. */
        CUSTOM("custom", null);

        companion object {
            fun fromOrNull(key: String?): Provider? = entries.firstOrNull { it.key == key }
        }
    }

    const val NEXTDNS_BASE = "https://dns.nextdns.io/"

    /**
     * Providers that were offered once and removed. Mullvad's public DoH shuts down on 2 Nov 2026 (Mullvad now
     * sponsors Quad9, which is what Mullvad Browser moved to).
     */
    private val REMOVED_PROVIDERS = mapOf("mullvad" to Provider.QUAD9)

    /** The provider and mode actually used, after falling back from removed or unknown saved values. */
    data class Choice(val mode: Mode, val provider: Provider, val fellBack: Boolean = false)

    /**
     * Resolves the saved prefs. A saved provider that no longer exists (e.g. Mullvad) is replaced and Strict is
     * relaxed to Automatic, so a vanished resolver can never leave the browser unable to load anything.
     */
    fun choice(modeKey: String?, providerKey: String?): Choice {
        // Pre-release 2.5 builds stored Off as a provider.
        if (providerKey == "off" && modeKey == null) return Choice(Mode.OFF, DEFAULT_PROVIDER, fellBack = true)
        val mode = Mode.from(modeKey)
        if (providerKey == null) return Choice(mode, DEFAULT_PROVIDER)
        Provider.fromOrNull(providerKey)?.let { return Choice(mode, it) }
        val replacement = REMOVED_PROVIDERS[providerKey] ?: DEFAULT_PROVIDER
        return Choice(if (mode == Mode.MAX) Mode.DEFAULT else mode, replacement, fellBack = true)
    }

    enum class UrlError { EMPTY, NOT_HTTPS, NO_HOST, MALFORMED, TOO_LONG }

    sealed class UrlCheck {
        data class Ok(val url: String) : UrlCheck()
        data class Invalid(val error: UrlError) : UrlCheck()
    }

    const val MAX_URL_LENGTH = 2048

    /** Validates a custom DoH endpoint: absolute https URL with a host, no credentials, fragment or spaces. */
    fun validateCustomUrl(input: String?): UrlCheck {
        val s = input?.trim().orEmpty()
        if (s.isEmpty()) return UrlCheck.Invalid(UrlError.EMPTY)
        if (s.length > MAX_URL_LENGTH) return UrlCheck.Invalid(UrlError.TOO_LONG)
        if (s.any { it.isWhitespace() || it.code < 0x20 }) return UrlCheck.Invalid(UrlError.MALFORMED)
        val uri = try { URI(s) } catch (_: Exception) { return UrlCheck.Invalid(UrlError.MALFORMED) }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return UrlCheck.Invalid(UrlError.NOT_HTTPS)
        if (scheme != "https") return UrlCheck.Invalid(UrlError.NOT_HTTPS)
        if (uri.isOpaque) return UrlCheck.Invalid(UrlError.MALFORMED)
        if (uri.rawUserInfo != null || uri.rawFragment != null) return UrlCheck.Invalid(UrlError.MALFORMED)
        // java.net.URI leaves host null for anything that isn't a valid hostname / IP literal.
        val host = uri.host
        if (host.isNullOrEmpty()) return UrlCheck.Invalid(UrlError.NO_HOST)
        // A bare word (e.g. "https://nextdns") isn't a public resolver; IPv6 literals are fine.
        if (!host.contains('.') && !host.startsWith("[")) return UrlCheck.Invalid(UrlError.NO_HOST)
        if (uri.port != -1 && uri.port !in 1..65535) return UrlCheck.Invalid(UrlError.MALFORMED)
        return UrlCheck.Ok("https" + s.substring(scheme.length)) // normalise "HTTPS://"
    }

    private val NEXTDNS_ID = Regex("^[a-z0-9]{6}$")

    /**
     * NextDNS config ID (6 characters, e.g. "abc123"), normalised to lower case. A pasted profile link
     * (https://dns.nextdns.io/abc123) is accepted too. null if invalid.
     */
    fun normalizeNextDnsId(input: String?): String? {
        var s = input?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (s.startsWith(NEXTDNS_BASE)) s = s.removePrefix(NEXTDNS_BASE).trimEnd('/')
        return s.takeIf { NEXTDNS_ID.matches(it) }
    }

    /** What to hand Gecko: [trrMode] is a GeckoRuntimeSettings.TRR_MODE_* value; [uri] null = leave the URI alone. */
    data class Config(val trrMode: Int, val uri: String?, val customInvalid: Boolean = false) {
        val enabled: Boolean get() = trrMode == GeckoRuntimeSettings.TRR_MODE_FIRST || trrMode == GeckoRuntimeSettings.TRR_MODE_ONLY
    }

    fun trrMode(mode: Mode): Int = when (mode) {
        Mode.OFF -> GeckoRuntimeSettings.TRR_MODE_DISABLED
        Mode.DEFAULT -> GeckoRuntimeSettings.TRR_MODE_FIRST
        Mode.MAX -> GeckoRuntimeSettings.TRR_MODE_ONLY
    }

    /** Endpoint for [provider], or null when NextDNS / Custom has no valid value. */
    fun endpoint(provider: Provider, nextDnsId: String?, customUrl: String?): String? = when (provider) {
        Provider.NEXTDNS -> normalizeNextDnsId(nextDnsId)?.let { NEXTDNS_BASE + it }
        Provider.CUSTOM -> (validateCustomUrl(customUrl) as? UrlCheck.Ok)?.url
        else -> provider.uri
    }

    /** Maps the prefs to a Gecko TRR config. NextDNS / Custom without a valid value behaves like Off. */
    fun config(modeKey: String?, providerKey: String?, nextDnsId: String?, customUrl: String?): Config {
        val c = choice(modeKey, providerKey)
        if (c.mode == Mode.OFF) return Config(GeckoRuntimeSettings.TRR_MODE_DISABLED, null)
        val uri = endpoint(c.provider, nextDnsId, customUrl)
            ?: return Config(GeckoRuntimeSettings.TRR_MODE_DISABLED, null, customInvalid = true)
        return Config(trrMode(c.mode), uri)
    }
}
